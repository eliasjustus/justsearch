package io.justsearch.ipc.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import com.google.protobuf.Empty;
import io.grpc.MethodDescriptor;
import io.justsearch.ipc.grpc.RequestIdClientInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

final class RequestIdClientInterceptorTest {
  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void usesRequestIdFromMdc() {
    MDC.put("request_id", "ai-req");
    CapturingCall call = new CapturingCall();
    RequestIdClientInterceptor interceptor = new RequestIdClientInterceptor();
    ClientCall<Empty, Empty> intercepted =
        interceptor.interceptCall(method(), CallOptions.DEFAULT, call.asChannel());
    intercepted.start(new ClientCall.Listener<>() {}, new Metadata());

    Metadata.Key<String> header =
        Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
    assertEquals("ai-req", call.headers.get(header));
  }

  @Test
  void generatesRequestIdWhenNotInMdc() {
    CapturingCall call = new CapturingCall();
    RequestIdClientInterceptor interceptor = new RequestIdClientInterceptor();
    ClientCall<Empty, Empty> intercepted =
        interceptor.interceptCall(method(), CallOptions.DEFAULT, call.asChannel());
    intercepted.start(new ClientCall.Listener<>() {}, new Metadata());

    Metadata.Key<String> header =
        Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
    String value = call.headers.get(header);
    assertNotNull(value);
    org.junit.jupiter.api.Assertions.assertTrue(value.contains("-"));
  }

  private MethodDescriptor<Empty, Empty> method() {
    return MethodDescriptor.<Empty, Empty>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("test/Method")
        .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(Empty.getDefaultInstance()))
        .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(Empty.getDefaultInstance()))
        .build();
  }

  private static final class CapturingCall extends ClientCall<Empty, Empty> {
    private final Metadata headers = new Metadata();

    @Override
    public void start(Listener<Empty> responseListener, Metadata headers) {
      this.headers.merge(headers);
    }

    @Override
    public void request(int numMessages) {}

    @Override
    public void cancel(String message, Throwable cause) {}

    @Override
    public void halfClose() {}

    @Override
    public void sendMessage(Empty message) {}

    Channel asChannel() {
      return new Channel() {
        @Override
        public String authority() {
          return "test";
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
          @SuppressWarnings("unchecked")
          ClientCall<ReqT, RespT> cast = (ClientCall<ReqT, RespT>) CapturingCall.this;
          return cast;
        }
      };
    }
  }
}
