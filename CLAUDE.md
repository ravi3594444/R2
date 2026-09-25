# Wakey: working notes for Claude

What the owner asked to be remembered (September 2026):

- First priority: make the agent smart and fast. Fewer model calls, fewer taps, direct
  actions where the request is clear. Speed is judged by "could I have done it faster by hand?".
- Don't over-complicate. Scheduling ("call mum at 3 am", "… after this") must work, but it is
  a side feature nobody will use much: keep it simple and never let it slow or complicate the
  main path (hear the request, do it).
- Don't ask again for what the request already asked for. Confirm only money, deleting and
  account changes.
- Work on the `claude/jev-agent` branch (the jev features stay), keep the unit tests and lint
  green, bump the version for every change and send the split APKs (arm64-v8a first).
- API keys never go into code, Git or logs. Keys are built into an APK only via inline
  environment variables with the owner's explicit, manual approval for that build.
- The owner writes quickly and informally; answer plainly and briefly, in their terms.
