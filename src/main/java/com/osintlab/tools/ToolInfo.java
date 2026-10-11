package com.osintlab.tools;

import java.util.Set;

/**
 * Catalog entry for one tool.
 *
 * @param accepts   target types this tool can run against
 * @param defaultOn pre-checked when a matching target is entered
 * @param slow      takes long or is noisy - shown with a marker in the UI
 * @param ready     an {@link OsintTool} implementation exists
 */
public record ToolInfo(String id, String name, String category, String description,
                       Set<TargetType> accepts, boolean defaultOn, boolean slow, boolean ready) {

    ToolInfo withReady(boolean ready) {
        return new ToolInfo(id, name, category, description, accepts, defaultOn, slow, ready);
    }
}
