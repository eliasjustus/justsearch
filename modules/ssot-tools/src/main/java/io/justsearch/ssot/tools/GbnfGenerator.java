/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ssot.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the GBNF grammar file for llama-server grammar-guided generation.
 *
 * <p>Replaces {@code SSOT/tools/generate-gbnf.mjs}. The grammar is a static string constrained
 * to the SearchIntent v1 shape — it is NOT derived from the JSON Schema at runtime.
 */
public final class GbnfGenerator {

  // @formatter:off
  private static final String GBNF_GRAMMAR =
      // Primitives
        "ws ::= [ \\t\\n\\r]*\n"
      + "boolean ::= \"true\" | \"false\"\n"
      + "integer ::= -? (0 | [1-9][0-9]*)\n"
      + "number ::= -? (0 | [1-9][0-9]*) (\\.[0-9]+)? ([eE] [+-]? [0-9]+)?\n"
      + "string ::= '\"' characters '\"'\n"
      + "characters ::= character*\n"
      + "character ::= [^\"\\\\] | escape\n"
      + "escape ::= '\\\"' | '\\\\' | '\\/' | '\\b' | '\\f' | '\\n' | '\\r' | '\\t' | unicode\n"
      + "unicode ::= '\\u' [0-9a-fA-F]{4}\n"
      // Containers
      + "array_of_strings ::= \"[\" ws (string (ws \",\" ws string)*)? ws \"]\"\n"
      + "array_of_clauses ::= \"[\" ws clause (ws \",\" ws clause)* ws \"]\"\n"
      // Intent object
      + "root ::= intent\n"
      + "intent ::= \"{\" ws intent_members ws \"}\"\n"
      + "intent_members ::= intent_pair (ws \",\" ws intent_pair)*\n"
      + "intent_pair ::= \"\\\"limit\\\"\" ws \":\" ws integer"
      + " | \"\\\"offset\\\"\" ws \":\" ws integer"
      + " | \"\\\"highlight\\\"\" ws \":\" ws boolean"
      + " | \"\\\"filters\\\"\" ws \":\" ws filters"
      + " | \"\\\"sort\\\"\" ws \":\" ws array_of_strings"
      + " | \"\\\"clauses\\\"\" ws \":\" ws array_of_clauses\n"
      // Filters
      + "filters ::= \"{\" ws filters_members? ws \"}\"\n"
      + "filters_members ::= filters_pair (ws \",\" ws filters_pair)*\n"
      + "filters_pair ::= \"\\\"mime\\\"\" ws \":\" ws string"
      + " | \"\\\"language\\\"\" ws \":\" ws string"
      + " | \"\\\"timeRange\\\"\" ws \":\" ws timeRange\n"
      // TimeRange
      + "timeRange ::= \"{\" ws time_members? ws \"}\"\n"
      + "time_members ::= time_pair (ws \",\" ws time_pair)*\n"
      + "time_pair ::= \"\\\"fromMs\\\"\" ws \":\" ws (integer | \"null\")"
      + " | \"\\\"toMs\\\"\" ws \":\" ws (integer | \"null\")\n"
      // Clause
      + "clause ::= \"{\" ws clause_members ws \"}\"\n"
      + "clause_members ::= clause_pair (ws \",\" ws clause_pair)*\n"
      + "clause_pair ::= \"\\\"type\\\"\" ws \":\" ws type_value"
      + " | \"\\\"field\\\"\" ws \":\" ws string"
      + " | \"\\\"value\\\"\" ws \":\" ws (string | number | boolean)"
      + " | \"\\\"tokens\\\"\" ws \":\" ws array_of_strings\n"
      // type is strictly lowercase letters
      + "type_value ::= '\"' [a-z]+ '\"'\n";
  // @formatter:on

  public static void main(String[] args) throws IOException {
    Path ssotRoot = Path.of(args.length > 0 ? args[0] : "SSOT");
    Path outDir = ssotRoot.resolve("artifacts/grammars");
    Files.createDirectories(outDir);

    Path gbnfPath = outDir.resolve("intent_v1.gbnf");
    Files.writeString(gbnfPath, GBNF_GRAMMAR, StandardCharsets.UTF_8);

    // Provenance manifest (sorted keys for determinism)
    String manifest =
        "{\"gbnf\":\"artifacts/grammars/intent_v1.gbnf\",\"source\":\"schemas/domain/search-intent.schema.json\"}";
    Files.writeString(outDir.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);

    System.out.printf("Generated GBNF at %s%n", gbnfPath);
  }

  private GbnfGenerator() {}
}
