---
name: python-developer
description: Best practices for developing Python applications: structure, dependencies, style, testing, logging, error handling, security, performance, packaging, and CI.
metadata:
  short-description: Python application best practices
---

# Python Developer Best Practices

## Use this skill when
- building or refactoring Python applications (CLI, desktop, services)
- setting up project structure, tooling, testing, or packaging
- implementing features with strong quality, security, and performance

## Default workflow
1. Plan module boundaries and repo layout.
2. Set up environment and dependencies (venv + pinned deps/lockfiles).
3. Implement with consistent style and type hints (PEP 8, formatter, linter).
4. Add docs and minimal comments for non-obvious logic.
5. Add error handling and logging.
6. Add tests (unit/integration) and coverage targets.
7. Profile and optimize only when needed.
8. Harden security (input validation, safe subprocess, parameterized SQL).
9. Package and document for distribution; ensure CI runs tests and linters.

## Implementation standards (summary)
- Prefer a clear package structure; avoid circular dependencies and hidden coupling.
- Use config files and environment variables; validate inputs and ranges.
- Use the logging module with module-level loggers; avoid print in apps.
- Catch specific exceptions; never swallow errors; log and re-raise when appropriate.
- Write tests with pytest (AAA pattern); mock external services.
- Profile before optimizing; fix algorithmic bottlenecks first.
- Use safe coding practices for file paths, subprocess, and SQL queries.

## References
- `references/best-practices-python.md` contains a condensed summary of the source PDF. Load it when you need details or examples.
