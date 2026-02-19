package com.transcribemate.v2.fx.modules.core;

/**
 * Immutable descriptor for module-specific workflow guidance card in UI.
 *
 * @param title      heading shown in flow card
 * @param details    ordered instructions displayed to user
 * @param actionKey  identifier consumed by MainController on action button click
 * @param actionText button label shown in flow card
 */
public record ModuleFlowSpec(
        String title,
        String details,
        String actionKey,
        String actionText
) {
}
