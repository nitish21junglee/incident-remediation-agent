package com.hackathon.incident_remediation_agent.ai;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Repoints a task's model chain without a restart.
 *
 * <p><strong>Unauthenticated.</strong> This prototype has no security layer, so anyone who can
 * reach the port can change which models incidents run on. The blast radius is deliberately
 * limited to model names: the base URL and API key are not reachable from here, so this cannot be
 * used to redirect prompts to another host or read the key. Do not expose the port publicly.
 */
@RestController
@RequestMapping("/admin/ai/models")
public class AiModelAdminController {

    private final AiModelSelector selector;

    AiModelAdminController(AiModelSelector selector) {
        this.selector = selector;
    }

    @GetMapping
    Map<String, List<String>> current() {
        return this.selector.snapshot();
    }

    @PutMapping("/{task}")
    Map<String, List<String>> override(@PathVariable String task, @RequestBody ModelChain chain) {
        this.selector.override(resolve(task), chain.models());
        return this.selector.snapshot();
    }

    @DeleteMapping("/{task}")
    ResponseEntity<Void> clear(@PathVariable String task) {
        this.selector.clearOverride(resolve(task));
        return ResponseEntity.noContent().build();
    }

    /** An unknown task is a missing resource; a bad chain is a bad request. */
    private static AiTask resolve(String task) {
        try {
            return AiTask.fromKey(task);
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> handleInvalidChain(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    record ModelChain(List<String> models) {}
}
