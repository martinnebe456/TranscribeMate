# FX Module Components

Component-based module structure for the JavaFX frontend.

- `core/`: shared contracts (`ModuleComponent`, `ModuleFlowSpec`, base class)
- `registry/`: module registration and lookup
- `<module_id>/`: one independent module component per folder

Each module folder contains its component class and a short README with defaults/constraints.
