1. Do not use closures for for-each. Method processData() must be early-returnable
2. Preferably use result pattern for method return statements `[status: status, message: message, payload: payload]` where
- **status**: -1 represents system/server error, 0 represents client error, 1 represents success
- **message**: must be of type _String_
- **payload** (Optional): must preferably be an API response payload from an API call.
3. Always append jdoc-style strings above a method when creating where
- **summary**: represents short definition of the method
- **params**: parameters of the method
- **description**: extended description
- **example**: use case scenario
4. Preferably code logger calls, and api calls in one line.

---
### AI Guidelines (workspace)

- **Docs scope**: When the agent needs to consult repository documentation, it must only read documentation under the `docs/ai` directory inside the repository. Do not read or infer from other `docs/` locations or external documents unless explicitly instructed.
- **Architecture reference**: For architecture context refer to the project README: [README.md](README.md). Use it as the primary architecture source for decision-making.
- **Behavior**: Follow workspace coding rules above when generating or editing code; call out when a requested change would violate these rules.
- **Modes / argument-hint**: If a skill or prompt exposes `dry-run` or `auto` modes, `dry-run` must never perform changes and should only show the git commands to run; `auto` may suggest and produce commands but must ask for explicit confirmation before executing any repository-modifying operations.

---