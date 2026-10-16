import resource, subprocess, sys, time
t = time.monotonic(); out = subprocess.run(sys.argv[2:], capture_output=True, text=True)
wall = time.monotonic() - t
rss = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
print(f"{sys.argv[1]} exit {out.returncode} wall {wall:.2f} s maxrss {rss} KB | {out.stdout.strip()}")
if sys.argv[1].endswith("1"): print(out.stderr)
