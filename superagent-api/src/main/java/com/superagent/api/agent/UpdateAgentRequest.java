package com.superagent.api.agent;

import java.util.List;

/**
 * Request to update an Agent's name / character / persona / default model / skills / tier. A field left out (null)
 * keeps its current value, so a caller sends only what it changes. A blank string clears: {@code persona} goes empty,
 * {@code model} back to the system's default model, {@code character} to a custom character, {@code tier} to the
 * default tier. {@code name} and {@code avatar} can't be cleared (blank keeps them). {@code skillIds} null keeps the
 * current skills; [] detaches all.
 */
public record UpdateAgentRequest(String name, String persona, String model, String avatar, String character,
                                 List<String> skillIds, String tier) {

    public UpdateAgentRequest(String name, String persona, String model, String avatar, String character, List<String> skillIds) {
        this(name, persona, model, avatar, character, skillIds, null);
    }
}
