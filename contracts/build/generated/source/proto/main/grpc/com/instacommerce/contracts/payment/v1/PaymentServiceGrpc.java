package com.instacommerce.contracts.payment.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.62.2)",
    comments = "Source: payment/v1/payment_service.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class PaymentServiceGrpc {

  private PaymentServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "payment.v1.PaymentService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.instacommerce.contracts.payment.v1.AuthorizeRequest,
      com.instacommerce.contracts.payment.v1.AuthorizeResponse> getAuthorizePaymentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "AuthorizePayment",
      requestType = com.instacommerce.contracts.payment.v1.AuthorizeRequest.class,
      responseType = com.instacommerce.contracts.payment.v1.AuthorizeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.instacommerce.contracts.payment.v1.AuthorizeRequest,
      com.instacommerce.contracts.payment.v1.AuthorizeResponse> getAuthorizePaymentMethod() {
    io.grpc.MethodDescriptor<com.instacommerce.contracts.payment.v1.AuthorizeRequest, com.instacommerce.contracts.payment.v1.AuthorizeResponse> getAuthorizePaymentMethod;
    if ((getAuthorizePaymentMethod = PaymentServiceGrpc.getAuthorizePaymentMethod) == null) {
      synchronized (PaymentServiceGrpc.class) {
        if ((getAuthorizePaymentMethod = PaymentServiceGrpc.getAuthorizePaymentMethod) == null) {
          PaymentServiceGrpc.getAuthorizePaymentMethod = getAuthorizePaymentMethod =
              io.grpc.MethodDescriptor.<com.instacommerce.contracts.payment.v1.AuthorizeRequest, com.instacommerce.contracts.payment.v1.AuthorizeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AuthorizePayment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.payment.v1.AuthorizeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.payment.v1.AuthorizeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PaymentServiceMethodDescriptorSupplier("AuthorizePayment"))
              .build();
        }
      }
    }
    return getAuthorizePaymentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.instacommerce.contracts.payment.v1.CaptureRequest,
      com.instacommerce.contracts.payment.v1.CaptureResponse> getCapturePaymentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CapturePayment",
      requestType = com.instacommerce.contracts.payment.v1.CaptureRequest.class,
      responseType = com.instacommerce.contracts.payment.v1.CaptureResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.instacommerce.contracts.payment.v1.CaptureRequest,
      com.instacommerce.contracts.payment.v1.CaptureResponse> getCapturePaymentMethod() {
    io.grpc.MethodDescriptor<com.instacommerce.contracts.payment.v1.CaptureRequest, com.instacommerce.contracts.payment.v1.CaptureResponse> getCapturePaymentMethod;
    if ((getCapturePaymentMethod = PaymentServiceGrpc.getCapturePaymentMethod) == null) {
      synchronized (PaymentServiceGrpc.class) {
        if ((getCapturePaymentMethod = PaymentServiceGrpc.getCapturePaymentMethod) == null) {
          PaymentServiceGrpc.getCapturePaymentMethod = getCapturePaymentMethod =
              io.grpc.MethodDescriptor.<com.instacommerce.contracts.payment.v1.CaptureRequest, com.instacommerce.contracts.payment.v1.CaptureResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CapturePayment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.payment.v1.CaptureRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.payment.v1.CaptureResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PaymentServiceMethodDescriptorSupplier("CapturePayment"))
              .build();
        }
      }
    }
    return getCapturePaymentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.instacommerce.contracts.payment.v1.VoidRequest,
      com.instacommerce.contracts.payment.v1.VoidResponse> getVoidAuthorizationMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "VoidAuthorization",
      requestType = com.instacommerce.contracts.payment.v1.VoidRequest.class,
      responseType = com.instacommerce.contracts.payment.v1.VoidResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.instacommerce.contracts.payment.v1.VoidRequest,
      com.instacommerce.contracts.payment.v1.VoidResponse> getVoidAuthorizationMethod() {
    io.grpc.MethodDescriptor<com.instacommerce.contracts.payment.v1.VoidRequest, com.instacommerce.contracts.payment.v1.VoidResponse> getVoidAuthorizationMethod;
    if ((getVoidAuthorizationMethod = PaymentServiceGrpc.getVoidAuthorizationMethod) == null) {
      synchronized (PaymentServiceGrpc.class) {
        if ((getVoidAuthorizationMethod = PaymentServiceGrpc.getVoidAuthorizationMethod) == null) {
          PaymentServiceGrpc.getVoidAuthorizationMethod = getVoidAuthorizationMethod =
              io.grpc.MethodDescriptor.<com.instacommerce.contracts.payment.v1.VoidRequest, com.instacommerce.contracts.payment.v1.VoidResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "VoidAuthorization"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.payment.v1.VoidRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.payment.v1.VoidResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PaymentServiceMethodDescriptorSupplier("VoidAuthorization"))
              .build();
        }
      }
    }
    return getVoidAuthorizationMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.instacommerce.contracts.payment.v1.RefundRequest,
      com.instacommerce.contracts.payment.v1.RefundResponse> getRefundPaymentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RefundPayment",
      requestType = com.instacommerce.contracts.payment.v1.RefundRequest.class,
      responseType = com.instacommerce.contracts.payment.v1.RefundResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.instacommerce.contracts.payment.v1.RefundRequest,
      com.instacommerce.contracts.payment.v1.RefundResponse> getRefundPaymentMethod() {
    io.grpc.MethodDescriptor<com.instacommerce.contracts.payment.v1.RefundRequest, com.instacommerce.contracts.payment.v1.RefundResponse> getRefundPaymentMethod;
    if ((getRefundPaymentMethod = PaymentServiceGrpc.getRefundPaymentMethod) == null) {
      synchronized (PaymentServiceGrpc.class) {
        if ((getRefundPaymentMethod = PaymentServiceGrpc.getRefundPaymentMethod) == null) {
          PaymentServiceGrpc.getRefundPaymentMethod = getRefundPaymentMethod =
              io.grpc.MethodDescriptor.<com.instacommerce.contracts.payment.v1.RefundRequest, com.instacommerce.contracts.payment.v1.RefundResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RefundPayment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.payment.v1.RefundRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.payment.v1.RefundResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PaymentServiceMethodDescriptorSupplier("RefundPayment"))
              .build();
        }
      }
    }
    return getRefundPaymentMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static PaymentServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PaymentServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PaymentServiceStub>() {
        @java.lang.Override
        public PaymentServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PaymentServiceStub(channel, callOptions);
        }
      };
    return PaymentServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static PaymentServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PaymentServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PaymentServiceBlockingStub>() {
        @java.lang.Override
        public PaymentServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PaymentServiceBlockingStub(channel, callOptions);
        }
      };
    return PaymentServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static PaymentServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PaymentServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PaymentServiceFutureStub>() {
        @java.lang.Override
        public PaymentServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PaymentServiceFutureStub(channel, callOptions);
        }
      };
    return PaymentServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void authorizePayment(com.instacommerce.contracts.payment.v1.AuthorizeRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.payment.v1.AuthorizeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAuthorizePaymentMethod(), responseObserver);
    }

    /**
     */
    default void capturePayment(com.instacommerce.contracts.payment.v1.CaptureRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.payment.v1.CaptureResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCapturePaymentMethod(), responseObserver);
    }

    /**
     */
    default void voidAuthorization(com.instacommerce.contracts.payment.v1.VoidRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.payment.v1.VoidResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getVoidAuthorizationMethod(), responseObserver);
    }

    /**
     */
    default void refundPayment(com.instacommerce.contracts.payment.v1.RefundRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.payment.v1.RefundResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRefundPaymentMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service PaymentService.
   */
  public static abstract class PaymentServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return PaymentServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service PaymentService.
   */
  public static final class PaymentServiceStub
      extends io.grpc.stub.AbstractAsyncStub<PaymentServiceStub> {
    private PaymentServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PaymentServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PaymentServiceStub(channel, callOptions);
    }

    /**
     */
    public void authorizePayment(com.instacommerce.contracts.payment.v1.AuthorizeRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.payment.v1.AuthorizeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAuthorizePaymentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void capturePayment(com.instacommerce.contracts.payment.v1.CaptureRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.payment.v1.CaptureResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCapturePaymentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void voidAuthorization(com.instacommerce.contracts.payment.v1.VoidRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.payment.v1.VoidResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getVoidAuthorizationMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void refundPayment(com.instacommerce.contracts.payment.v1.RefundRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.payment.v1.RefundResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRefundPaymentMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service PaymentService.
   */
  public static final class PaymentServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<PaymentServiceBlockingStub> {
    private PaymentServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PaymentServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PaymentServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.instacommerce.contracts.payment.v1.AuthorizeResponse authorizePayment(com.instacommerce.contracts.payment.v1.AuthorizeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAuthorizePaymentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.instacommerce.contracts.payment.v1.CaptureResponse capturePayment(com.instacommerce.contracts.payment.v1.CaptureRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCapturePaymentMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.instacommerce.contracts.payment.v1.VoidResponse voidAuthorization(com.instacommerce.contracts.payment.v1.VoidRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getVoidAuthorizationMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.instacommerce.contracts.payment.v1.RefundResponse refundPayment(com.instacommerce.contracts.payment.v1.RefundRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRefundPaymentMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service PaymentService.
   */
  public static final class PaymentServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<PaymentServiceFutureStub> {
    private PaymentServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PaymentServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PaymentServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.instacommerce.contracts.payment.v1.AuthorizeResponse> authorizePayment(
        com.instacommerce.contracts.payment.v1.AuthorizeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAuthorizePaymentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.instacommerce.contracts.payment.v1.CaptureResponse> capturePayment(
        com.instacommerce.contracts.payment.v1.CaptureRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCapturePaymentMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.instacommerce.contracts.payment.v1.VoidResponse> voidAuthorization(
        com.instacommerce.contracts.payment.v1.VoidRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getVoidAuthorizationMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.instacommerce.contracts.payment.v1.RefundResponse> refundPayment(
        com.instacommerce.contracts.payment.v1.RefundRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRefundPaymentMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_AUTHORIZE_PAYMENT = 0;
  private static final int METHODID_CAPTURE_PAYMENT = 1;
  private static final int METHODID_VOID_AUTHORIZATION = 2;
  private static final int METHODID_REFUND_PAYMENT = 3;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_AUTHORIZE_PAYMENT:
          serviceImpl.authorizePayment((com.instacommerce.contracts.payment.v1.AuthorizeRequest) request,
              (io.grpc.stub.StreamObserver<com.instacommerce.contracts.payment.v1.AuthorizeResponse>) responseObserver);
          break;
        case METHODID_CAPTURE_PAYMENT:
          serviceImpl.capturePayment((com.instacommerce.contracts.payment.v1.CaptureRequest) request,
              (io.grpc.stub.StreamObserver<com.instacommerce.contracts.payment.v1.CaptureResponse>) responseObserver);
          break;
        case METHODID_VOID_AUTHORIZATION:
          serviceImpl.voidAuthorization((com.instacommerce.contracts.payment.v1.VoidRequest) request,
              (io.grpc.stub.StreamObserver<com.instacommerce.contracts.payment.v1.VoidResponse>) responseObserver);
          break;
        case METHODID_REFUND_PAYMENT:
          serviceImpl.refundPayment((com.instacommerce.contracts.payment.v1.RefundRequest) request,
              (io.grpc.stub.StreamObserver<com.instacommerce.contracts.payment.v1.RefundResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getAuthorizePaymentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.instacommerce.contracts.payment.v1.AuthorizeRequest,
              com.instacommerce.contracts.payment.v1.AuthorizeResponse>(
                service, METHODID_AUTHORIZE_PAYMENT)))
        .addMethod(
          getCapturePaymentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.instacommerce.contracts.payment.v1.CaptureRequest,
              com.instacommerce.contracts.payment.v1.CaptureResponse>(
                service, METHODID_CAPTURE_PAYMENT)))
        .addMethod(
          getVoidAuthorizationMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.instacommerce.contracts.payment.v1.VoidRequest,
              com.instacommerce.contracts.payment.v1.VoidResponse>(
                service, METHODID_VOID_AUTHORIZATION)))
        .addMethod(
          getRefundPaymentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.instacommerce.contracts.payment.v1.RefundRequest,
              com.instacommerce.contracts.payment.v1.RefundResponse>(
                service, METHODID_REFUND_PAYMENT)))
        .build();
  }

  private static abstract class PaymentServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    PaymentServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.instacommerce.contracts.payment.v1.PaymentServiceOuterClass.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("PaymentService");
    }
  }

  private static final class PaymentServiceFileDescriptorSupplier
      extends PaymentServiceBaseDescriptorSupplier {
    PaymentServiceFileDescriptorSupplier() {}
  }

  private static final class PaymentServiceMethodDescriptorSupplier
      extends PaymentServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    PaymentServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (PaymentServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new PaymentServiceFileDescriptorSupplier())
              .addMethod(getAuthorizePaymentMethod())
              .addMethod(getCapturePaymentMethod())
              .addMethod(getVoidAuthorizationMethod())
              .addMethod(getRefundPaymentMethod())
              .build();
        }
      }
    }
    return result;
  }
}
