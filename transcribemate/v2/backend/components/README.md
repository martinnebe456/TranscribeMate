# Backend Module Components

Component-based module layout for backend processing.

- `core/`: common contracts, static component helper, shared pipeline adapter
- `registry.py`: component registration/lookup
- `<module_id>/`: independent component package with own `component.py`

Each module package includes a short README describing runtime profile and enforced request contract.
