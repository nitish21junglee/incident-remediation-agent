package com.hackathon.incident_remediation_agent.ai;

import java.util.Map;

import com.hackathon.incident_remediation_agent.evidence.EvidencePack;

public interface AiFixClient {

    /**
     * @param repositoryFiles insertion-ordered map of repository-relative path to file content,
     *                        chosen from the incident's stack frames; only files under the
     *                        repository's {@code writable-paths} may appear
     */
    FixProposal propose(EvidencePack pack, Map<String, String> repositoryFiles);
}
