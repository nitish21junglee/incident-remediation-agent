package com.hackathon.incident_remediation_agent.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;

/**
 * The head commit of the base branch, diffed by GitHub against its first parent. Pinned here
 * because a merge commit's first parent is the branch tip it merged into, which is what makes one
 * call answer "what landed last, against what was stable before it".
 */
class GitHubChangeCollectorTest {

    private static final String BASE = "https://api.github.com";

    private static final String COMMITS = BASE + "/repos/Flutter-Global/darsrftp-service/commits/dev";

    private static final String PULLS = BASE + "/repos/Flutter-Global/darsrftp-service/pulls"
        + "?state=closed&base=dev&sort=updated&direction=desc&per_page=10";

    private static final AgentProperties.RepositoryTarget TARGET =
        new AgentProperties.RepositoryTarget("Flutter-Global/darsrftp-service", "dev",
            List.of("src/main/java/"), List.of("src/main/java/com/flutter/reward_service/"));

    private MockRestServiceServer server;

    private GitHubChangeCollector collector;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.collector = new GitHubChangeCollector(builder, new AgentProperties(
            "live", "P2UX5VH", null, null, null, null, null, null,
            new AgentProperties.GitHub(BASE, "token", "agent/incident", false, 5,
                List.of(), Map.of(), Map.of())));
    }

    @Test
    void collectsTheHeadCommitDiffAndTheLastMergedPullRequest() {
        respond(COMMITS, """
            {"sha":"033f961bfbc4ddb6e26d79e9a4bdc2b82dc2deb9",
             "commit":{"message":"Merge pull request #290","author":{"date":"2026-08-21T17:53:34Z"}},
             "files":[{"filename":"src/main/java/RewardServiceImpl.java",
                       "patch":"@@ -293,6 +293,8 @@\\n+        int a = 1/0;"}]}""");
        respond(PULLS, """
            [{"number":291,"title":"not merged","merged_at":null,"html_url":"https://gh/291"},
             {"number":290,"title":"bug","merged_at":"2026-08-21T17:53:34Z","html_url":"https://gh/290"}]""");

        RepositoryChange change = this.collector.collect(TARGET);

        this.server.verify();
        assertThat(change.commitSha()).isEqualTo("033f961bfbc4ddb6e26d79e9a4bdc2b82dc2deb9");
        assertThat(change.committedAt()).isEqualTo(Instant.parse("2026-08-21T17:53:34Z"));
        assertThat(change.diff())
            .contains("--- src/main/java/RewardServiceImpl.java")
            .contains("int a = 1/0;");
        assertThat(change.truncatedFiles()).isZero();
        // A closed-but-unmerged pull request changed nothing on the branch, so it is skipped.
        assertThat(change.lastPullRequest()).isEqualTo(new RepositoryChange.PullRequest(
            290, "bug", URI.create("https://gh/290"), Instant.parse("2026-08-21T17:53:34Z")));
    }

    /** Patches are kept or dropped whole, so what reaches the prompt is still a valid diff. */
    @Test
    void dropsWholeFilePatchesOnceTheDiffBudgetIsSpent() {
        respond(COMMITS, """
            {"sha":"abc","commit":{"message":"big","author":{"date":"2026-08-21T17:53:34Z"}},
             "files":[{"filename":"Small.java","patch":"@@ small @@"},
                      {"filename":"Huge.java","patch":"%s"}]}""".formatted("x".repeat(20_001)));
        respond(PULLS, "[]");

        RepositoryChange change = this.collector.collect(TARGET);

        assertThat(change.diff()).contains("Small.java").doesNotContain("Huge.java");
        assertThat(change.truncatedFiles()).isEqualTo(1);
        assertThat(change.lastPullRequest()).isNull();
    }

    /** Losing the diff must not cost an investigation that still has logs and metrics. */
    @Test
    void returnsNullWhenGitHubCannotBeRead() {
        this.server.expect(requestTo(COMMITS)).andRespond(withServerError());

        assertThat(this.collector.collect(TARGET)).isNull();
    }

    private void respond(String uri, String body) {
        this.server.expect(requestTo(uri))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
