# Best Practices for Developing Python Applications (Condensed)
Source: Best Practices for Developing Python Applications.pdf

## Project structure and modularization
- Use a predictable repo layout. Include README, LICENSE, pyproject.toml, tests/, and docs/ if needed.
- Keep code in an importable package (src layout or flat layout). Avoid many loose files.
- Separate UI, business logic, and data access layers.
- Avoid circular dependencies, hidden coupling, and excessive global state.

## Dependency management and environments
- Use virtual environments (venv, virtualenv, conda) to isolate deps.
- Track dependencies in requirements.txt, Pipfile, or pyproject.toml.
- Use lockfiles for reproducible builds (pip-tools, Pipenv, Poetry).
- Document setup steps for dev and CI.

## Code style, formatting, docs, and types
- Follow PEP 8 naming and formatting guidelines.
- Use a formatter (Black) and a linter (flake8/pylint).
- Use meaningful names; write docstrings (Google or NumPy style).
- Add type hints; validate with mypy or IDE checks.
- Run style checks in pre-commit and CI.

## Error handling and exceptions
- Catch specific exceptions; avoid bare except.
- Never use `except Exception: pass`.
- Handle or log and re-raise errors; do not silently swallow failures.
- Use context managers or try/finally for cleanup.
- Raise exceptions for invalid input (EAFP).
- Use custom exception types for domain errors; translate low-level errors in higher layers.
- Log exceptions with logger.exception for stack traces.

## Logging
- Prefer the logging module over print.
- Use module-level loggers: `logger = logging.getLogger(__name__)`.
- Use proper levels: DEBUG, INFO, WARNING, ERROR, CRITICAL.
- Configure handlers and format at the entry point; include timestamps and module names.
- Avoid noisy logs in hot loops; log key events and failures.

## Testing
- Use both unit and integration tests; cover edge cases.
- Prefer pytest; organize tests/ mirroring package structure.
- Follow Arrange-Act-Assert; keep tests focused.
- Mock external services (network, DB, filesystem) in unit tests.
- Use parametrized tests for variations.
- Track coverage (pytest-cov) and gate in CI.

## Continuous integration
- Run tests, linting, and formatting in CI.
- Fail fast on broken tests; keep CI deterministic.
- Treat tests as living documentation for expected behavior.

## Performance and profiling
- Profile before optimizing (cProfile, snakeviz, pyinstrument).
- Use tracemalloc or memory_profiler for memory issues.
- Optimize algorithms and data structures first.
- Use vectorization (NumPy) or native libs for heavy compute.
- Consider caching and batching to reduce repeated work.

## Security practices
- Validate and sanitize all external input.
- Use parameterized SQL queries.
- Avoid eval/exec on untrusted input; use safe alternatives.
- Use safe file handling: normalize paths, restrict to allowed dirs.
- Use subprocess safely (list args, no shell).
- Apply least privilege; avoid writing to sensitive locations.

## Configuration management
- Use config files (JSON/INI/YAML) with validation.
- Use safe YAML loaders (safe_load).
- Validate types and ranges (example: port number).
- Keep secrets out of repo; prefer env vars or secret stores.

## Packaging and distribution
- Use pyproject.toml with setuptools/Flit/Poetry.
- Build sdist and wheels; include README and LICENSE.
- For desktop apps, consider PyInstaller or similar tools.
- Version releases and document install steps.
