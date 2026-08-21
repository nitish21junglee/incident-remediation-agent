package com.hackathon.incident_remediation_agent.ai;

/**
 * @param recommended whether the model is plausibly usable for a text fix proposal; image, speech,
 *                    music, embedding and robotics models are listed but not recommended
 */
public record AvailableModel(String id, boolean recommended) {}
