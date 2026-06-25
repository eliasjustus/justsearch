/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.json;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Javalin {@link io.javalin.json.JsonMapper} backed by Jackson 3's {@link ObjectMapper}.
 *
 * <p>Javalin's built-in {@code JavalinJackson} looks for {@code com.fasterxml.jackson} (Jackson
 * 2.x). Since we migrated to Jackson 3 ({@code tools.jackson}), we provide this adapter so Javalin
 * can serialize/deserialize JSON using the new API.
 */
public final class Jackson3JsonMapper implements io.javalin.json.JsonMapper {
  private final ObjectMapper mapper;

  public Jackson3JsonMapper() {
    this(tools.jackson.databind.json.JsonMapper.builder().build());
  }

  public Jackson3JsonMapper(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @NotNull
  @Override
  public String toJsonString(@NotNull Object obj, @NotNull Type type) {
    return mapper.writeValueAsString(obj);
  }

  @NotNull
  @Override
  public InputStream toJsonStream(@NotNull Object obj, @NotNull Type type) {
    return new ByteArrayInputStream(mapper.writeValueAsBytes(obj));
  }

  @Override
  public void writeToOutputStream(@NotNull Stream<?> stream, @NotNull OutputStream outputStream) {
    mapper.writeValue(outputStream, stream.toArray());
  }

  @NotNull
  @Override
  public <T> T fromJsonString(@NotNull String json, @NotNull Type type) {
    return mapper.readValue(json, new TypeReference<T>() {
      @Override
      public Type getType() {
        return type;
      }
    });
  }

  @NotNull
  @Override
  public <T> T fromJsonStream(@NotNull InputStream json, @NotNull Type type) {
    return mapper.readValue(json, new TypeReference<T>() {
      @Override
      public Type getType() {
        return type;
      }
    });
  }
}
