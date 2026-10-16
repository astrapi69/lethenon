/*
 * The MIT License
 *
 * Copyright (C) 2015 Asterios Raptis
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

/*
 * A record of every write and every sync a process makes to the files of one directory, for the
 * power-cut test of #202 (PowerCutTest).
 *
 * Loaded with LD_PRELOAD. IOLOG_DIR names the directory whose files are recorded, IOLOG_FILE the
 * record. Each record is: one byte for the operation, the path as a 16-bit length and its bytes,
 * then for a write the offset (64 bits), the length (32 bits) and the bytes written; for a
 * truncation the new length (64 bits), an open with O_TRUNC included; nothing more for a sync or
 * an unlink. Numbers are in the
 * machine's byte order. The operation is done first and recorded after it, under one lock, so the
 * record holds the operations in the order the file system saw them; a process killed between an
 * operation and its record loses the record, which counts that operation as never synced - the
 * cautious reading.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define TRACKED 4096

static char *paths[TRACKED];
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int record = -1;
static const char *directory;
static size_t directoryLength;

static int (*realOpen)(const char *, int, ...);
static int (*realOpen64)(const char *, int, ...);
static ssize_t (*realWrite)(int, const void *, size_t);
static ssize_t (*realPwrite)(int, const void *, size_t, off_t);
static ssize_t (*realPwrite64)(int, const void *, size_t, off64_t);
static int (*realFsync)(int);
static int (*realFdatasync)(int);
static int (*realFtruncate)(int, off_t);
static int (*realFtruncate64)(int, off64_t);
static int (*realClose)(int);
static int (*realUnlink)(const char *);

__attribute__((constructor)) static void start(void)
{
	realOpen = dlsym(RTLD_NEXT, "open");
	realOpen64 = dlsym(RTLD_NEXT, "open64");
	realWrite = dlsym(RTLD_NEXT, "write");
	realPwrite = dlsym(RTLD_NEXT, "pwrite");
	realPwrite64 = dlsym(RTLD_NEXT, "pwrite64");
	realFsync = dlsym(RTLD_NEXT, "fsync");
	realFdatasync = dlsym(RTLD_NEXT, "fdatasync");
	realFtruncate = dlsym(RTLD_NEXT, "ftruncate");
	realFtruncate64 = dlsym(RTLD_NEXT, "ftruncate64");
	realClose = dlsym(RTLD_NEXT, "close");
	realUnlink = dlsym(RTLD_NEXT, "unlink");
	directory = getenv("IOLOG_DIR");
	const char *file = getenv("IOLOG_FILE");
	if (directory != NULL && file != NULL)
	{
		directoryLength = strlen(directory);
		record = realOpen(file, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
	}
}

static int isTracked(const char *path)
{
	return record >= 0 && path != NULL && strncmp(path, directory, directoryLength) == 0;
}

static void put(const void *bytes, size_t length)
{
	const char *next = bytes;
	while (length > 0)
	{
		ssize_t written = realWrite(record, next, length);
		if (written <= 0)
		{
			abort();
		}
		next += written;
		length -= (size_t)written;
	}
}

static void putHead(char operation, const char *path)
{
	uint16_t length = (uint16_t)strlen(path);
	put(&operation, 1);
	put(&length, sizeof length);
	put(path, length);
}

static void remember(int fd, const char *path)
{
	if (fd >= 0 && fd < TRACKED && isTracked(path))
	{
		free(paths[fd]);
		paths[fd] = strdup(path);
	}
}

static const char *pathOf(int fd)
{
	return fd >= 0 && fd < TRACKED ? paths[fd] : NULL;
}

static int opened(int fd, const char *path, int flags)
{
	if (fd >= 0 && isTracked(path))
	{
		pthread_mutex_lock(&lock);
		remember(fd, path);
		if (flags & O_TRUNC)
		{
			int64_t length = 0;
			putHead('T', path);
			put(&length, sizeof length);
		}
		pthread_mutex_unlock(&lock);
	}
	return fd;
}

int open(const char *path, int flags, ...)
{
	va_list arguments;
	va_start(arguments, flags);
	mode_t mode = (flags & O_CREAT) ? va_arg(arguments, mode_t) : 0;
	va_end(arguments);
	return opened(realOpen(path, flags, mode), path, flags);
}

int open64(const char *path, int flags, ...)
{
	va_list arguments;
	va_start(arguments, flags);
	mode_t mode = (flags & O_CREAT) ? va_arg(arguments, mode_t) : 0;
	va_end(arguments);
	return opened(realOpen64(path, flags, mode), path, flags);
}

static ssize_t recordWrite(int fd, const void *bytes, ssize_t written, int64_t offset)
{
	if (written > 0)
	{
		uint32_t length = (uint32_t)written;
		putHead('W', pathOf(fd));
		put(&offset, sizeof offset);
		put(&length, sizeof length);
		put(bytes, length);
	}
	return written;
}

ssize_t write(int fd, const void *bytes, size_t length)
{
	if (fd == record || pathOf(fd) == NULL)
	{
		return realWrite(fd, bytes, length);
	}
	pthread_mutex_lock(&lock);
	int64_t offset = lseek(fd, 0, SEEK_CUR);
	ssize_t written = recordWrite(fd, bytes, realWrite(fd, bytes, length), offset);
	pthread_mutex_unlock(&lock);
	return written;
}

ssize_t pwrite(int fd, const void *bytes, size_t length, off_t offset)
{
	if (pathOf(fd) == NULL)
	{
		return realPwrite(fd, bytes, length, offset);
	}
	pthread_mutex_lock(&lock);
	ssize_t written = recordWrite(fd, bytes, realPwrite(fd, bytes, length, offset), offset);
	pthread_mutex_unlock(&lock);
	return written;
}

ssize_t pwrite64(int fd, const void *bytes, size_t length, off64_t offset)
{
	if (pathOf(fd) == NULL)
	{
		return realPwrite64(fd, bytes, length, offset);
	}
	pthread_mutex_lock(&lock);
	ssize_t written = recordWrite(fd, bytes, realPwrite64(fd, bytes, length, offset), offset);
	pthread_mutex_unlock(&lock);
	return written;
}

static int recordSync(int fd, int outcome)
{
	if (outcome == 0)
	{
		putHead('S', pathOf(fd));
	}
	return outcome;
}

int fsync(int fd)
{
	if (pathOf(fd) == NULL)
	{
		return realFsync(fd);
	}
	pthread_mutex_lock(&lock);
	int outcome = recordSync(fd, realFsync(fd));
	pthread_mutex_unlock(&lock);
	return outcome;
}

int fdatasync(int fd)
{
	if (pathOf(fd) == NULL)
	{
		return realFdatasync(fd);
	}
	pthread_mutex_lock(&lock);
	int outcome = recordSync(fd, realFdatasync(fd));
	pthread_mutex_unlock(&lock);
	return outcome;
}

static int recordTruncate(int fd, int64_t length, int outcome)
{
	if (outcome == 0)
	{
		putHead('T', pathOf(fd));
		put(&length, sizeof length);
	}
	return outcome;
}

int ftruncate(int fd, off_t length)
{
	if (pathOf(fd) == NULL)
	{
		return realFtruncate(fd, length);
	}
	pthread_mutex_lock(&lock);
	int outcome = recordTruncate(fd, length, realFtruncate(fd, length));
	pthread_mutex_unlock(&lock);
	return outcome;
}

int ftruncate64(int fd, off64_t length)
{
	if (pathOf(fd) == NULL)
	{
		return realFtruncate64(fd, length);
	}
	pthread_mutex_lock(&lock);
	int outcome = recordTruncate(fd, length, realFtruncate64(fd, length));
	pthread_mutex_unlock(&lock);
	return outcome;
}

int close(int fd)
{
	if (pathOf(fd) == NULL)
	{
		return realClose(fd);
	}
	pthread_mutex_lock(&lock);
	free(paths[fd]);
	paths[fd] = NULL;
	int outcome = realClose(fd);
	pthread_mutex_unlock(&lock);
	return outcome;
}

int unlink(const char *path)
{
	if (!isTracked(path))
	{
		return realUnlink(path);
	}
	pthread_mutex_lock(&lock);
	int outcome = realUnlink(path);
	if (outcome == 0)
	{
		putHead('U', path);
	}
	pthread_mutex_unlock(&lock);
	return outcome;
}
