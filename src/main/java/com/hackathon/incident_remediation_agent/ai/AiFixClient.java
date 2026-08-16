package com.hackathon.incident_remediation_agent.ai;

import java.util.Map;

import com.hackathon.incident_remediation_agent.evidence.EvidencePack;

public interface AiFixClient {

    /**
     * @param repositoryFiles insertion-ordered map of repository-relative path to file content;
     *                        only files listed in {@code agent.github.context-files} may appear
     */
    FixProposal propose(EvidencePack pack, Map<String, String> repositoryFiles);
}
