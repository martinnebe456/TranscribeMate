package com.transcribemate.v2.fx.modules.registry;

import com.transcribemate.v2.fx.modules.conference_mode.ConferenceModeModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleComponent;
import com.transcribemate.v2.fx.modules.offline_transcribe.OfflineTranscribeModuleComponent;
import com.transcribemate.v2.fx.modules.speaker_transcribe.SpeakerTranscriptModuleComponent;
import com.transcribemate.v2.fx.modules.youtube_dub.YoutubeDubModuleComponent;
import com.transcribemate.v2.fx.modules.youtube_subtitles.YoutubeSubtitlesModuleComponent;
import com.transcribemate.v2.fx.modules.youtube_transcribe.YoutubeTranscriptModuleComponent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * Central registry of frontend module components.
 *
 * Registry preserves explicit module ordering used by selectors and navigation.
 */
public final class ModuleRegistry {
    // Ordered list drives UI module order consistently across views.
    private static final List<ModuleComponent> ORDERED_COMPONENTS = List.of(
            new OfflineTranscribeModuleComponent(),
            new YoutubeTranscriptModuleComponent(),
            new SpeakerTranscriptModuleComponent(),
            new ConferenceModeModuleComponent(),
            new YoutubeSubtitlesModuleComponent(),
            new YoutubeDubModuleComponent()
    );

    // Immutable lookup maps exposed to callers.
    private static final Map<String, ModuleComponent> BY_ID;
    private static final Map<String, String> LABEL_TO_ID;

    static {
        LinkedHashMap<String, ModuleComponent> byId = new LinkedHashMap<>();
        LinkedHashMap<String, String> labelToId = new LinkedHashMap<>();
        for (ModuleComponent component : ORDERED_COMPONENTS) {
            byId.put(component.id(), component);
            labelToId.put(component.label(), component.id());
        }
        BY_ID = Collections.unmodifiableMap(byId);
        LABEL_TO_ID = Collections.unmodifiableMap(labelToId);
    }

    private ModuleRegistry() {
    }

    /**
     * Returns supported module ids in configured insertion order.
     */
    public static Set<String> supportedModuleIds() {
        return BY_ID.keySet();
    }

    public static List<ModuleComponent> orderedComponents() {
        return ORDERED_COMPONENTS;
    }

    public static Map<String, ModuleComponent> byId() {
        return BY_ID;
    }

    public static Map<String, String> labelToId() {
        return LABEL_TO_ID;
    }

    /**
     * Resolves module by id with offline module as defensive fallback.
     */
    public static ModuleComponent get(String moduleId) {
        ModuleComponent component = BY_ID.get(moduleId);
        if (component != null) {
            return component;
        }
        return BY_ID.get("offline_transcribe");
    }

    public static String labelFor(String moduleId) {
        return get(moduleId).label();
    }
}
