# Hermes → Swarm

Hermes is the external agent supervisor. SwarmBuilder is the builder engine.

## Local LLM

Default OpenAI-compatible endpoint:

`http://127.0.0.1:1234/v1`

Hermes should discover the model with:

`GET /v1/models`

and use the returned model id for OpenAI-compatible chat completions.

## Operating contract

When operating this repository, Hermes should:

1. Inspect git status and the current project before changing files.
2. Treat `SwarmOrchestrator` as the application-building engine.
3. Use Swarm's Architect → Coder → Reviewer → Builder → Repair → Publisher flow.
4. Run the Gradle build after changes.
5. Feed compiler/build failures back into the repair loop.
6. Preserve the last known-good project before risky repairs.
7. Never declare success until the build/test result is verified.
8. Keep the local OpenAI-compatible provider available as a first-class provider.
9. If the local endpoint fails, report the HTTP/status/body diagnostic and use a configured fallback provider when available.
10. Commit only verified changes.

Hermes supervises. Swarm builds.