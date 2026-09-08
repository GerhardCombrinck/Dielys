### Description

<!-- What changed and why. Link the issue: Closes #NN -->

### Protocol impact

<!-- MUST be filled in. One of:
     - No protocol change
     - Additive within version N (old clients unaffected)
     - Breaking — bumps PROTOCOL_VERSION to N, migration plan below -->

### How to test

<!-- Steps. For sync changes, name which scenario in H3 this exercises. -->

### Pre-merge checklist

- [ ] Formatter and linter pass locally
- [ ] Tests added or updated
- [ ] `protocol/fixtures/` updated if the wire format changed
- [ ] Migration added if schema changed, and tested against a populated DB
- [ ] No secret, key, or token in the diff
