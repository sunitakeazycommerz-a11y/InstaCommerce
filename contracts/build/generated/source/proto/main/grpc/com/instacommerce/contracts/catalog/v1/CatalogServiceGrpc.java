package com.instacommerce.contracts.catalog.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.62.2)",
    comments = "Source: catalog/v1/catalog_service.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class CatalogServiceGrpc {

  private CatalogServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "catalog.v1.CatalogService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.instacommerce.contracts.catalog.v1.GetProductDetailsRequest,
      com.instacommerce.contracts.catalog.v1.GetProductDetailsResponse> getGetProductDetailsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetProductDetails",
      requestType = com.instacommerce.contracts.catalog.v1.GetProductDetailsRequest.class,
      responseType = com.instacommerce.contracts.catalog.v1.GetProductDetailsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.instacommerce.contracts.catalog.v1.GetProductDetailsRequest,
      com.instacommerce.contracts.catalog.v1.GetProductDetailsResponse> getGetProductDetailsMethod() {
    io.grpc.MethodDescriptor<com.instacommerce.contracts.catalog.v1.GetProductDetailsRequest, com.instacommerce.contracts.catalog.v1.GetProductDetailsResponse> getGetProductDetailsMethod;
    if ((getGetProductDetailsMethod = CatalogServiceGrpc.getGetProductDetailsMethod) == null) {
      synchronized (CatalogServiceGrpc.class) {
        if ((getGetProductDetailsMethod = CatalogServiceGrpc.getGetProductDetailsMethod) == null) {
          CatalogServiceGrpc.getGetProductDetailsMethod = getGetProductDetailsMethod =
              io.grpc.MethodDescriptor.<com.instacommerce.contracts.catalog.v1.GetProductDetailsRequest, com.instacommerce.contracts.catalog.v1.GetProductDetailsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetProductDetails"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.catalog.v1.GetProductDetailsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.catalog.v1.GetProductDetailsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new CatalogServiceMethodDescriptorSupplier("GetProductDetails"))
              .build();
        }
      }
    }
    return getGetProductDetailsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.instacommerce.contracts.catalog.v1.GetProductsBatchRequest,
      com.instacommerce.contracts.catalog.v1.GetProductsBatchResponse> getGetProductsBatchMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetProductsBatch",
      requestType = com.instacommerce.contracts.catalog.v1.GetProductsBatchRequest.class,
      responseType = com.instacommerce.contracts.catalog.v1.GetProductsBatchResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.instacommerce.contracts.catalog.v1.GetProductsBatchRequest,
      com.instacommerce.contracts.catalog.v1.GetProductsBatchResponse> getGetProductsBatchMethod() {
    io.grpc.MethodDescriptor<com.instacommerce.contracts.catalog.v1.GetProductsBatchRequest, com.instacommerce.contracts.catalog.v1.GetProductsBatchResponse> getGetProductsBatchMethod;
    if ((getGetProductsBatchMethod = CatalogServiceGrpc.getGetProductsBatchMethod) == null) {
      synchronized (CatalogServiceGrpc.class) {
        if ((getGetProductsBatchMethod = CatalogServiceGrpc.getGetProductsBatchMethod) == null) {
          CatalogServiceGrpc.getGetProductsBatchMethod = getGetProductsBatchMethod =
              io.grpc.MethodDescriptor.<com.instacommerce.contracts.catalog.v1.GetProductsBatchRequest, com.instacommerce.contracts.catalog.v1.GetProductsBatchResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetProductsBatch"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.catalog.v1.GetProductsBatchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.catalog.v1.GetProductsBatchResponse.getDefaultInstance()))
              .setSchemaDescriptor(new CatalogServiceMethodDescriptorSupplier("GetProductsBatch"))
              .build();
        }
      }
    }
    return getGetProductsBatchMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static CatalogServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CatalogServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CatalogServiceStub>() {
        @java.lang.Override
        public CatalogServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CatalogServiceStub(channel, callOptions);
        }
      };
    return CatalogServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static CatalogServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CatalogServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CatalogServiceBlockingStub>() {
        @java.lang.Override
        public CatalogServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CatalogServiceBlockingStub(channel, callOptions);
        }
      };
    return CatalogServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static CatalogServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CatalogServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CatalogServiceFutureStub>() {
        @java.lang.Override
        public CatalogServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CatalogServiceFutureStub(channel, callOptions);
        }
      };
    return CatalogServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void getProductDetails(com.instacommerce.contracts.catalog.v1.GetProductDetailsRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.catalog.v1.GetProductDetailsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetProductDetailsMethod(), responseObserver);
    }

    /**
     */
    default void getProductsBatch(com.instacommerce.contracts.catalog.v1.GetProductsBatchRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.catalog.v1.GetProductsBatchResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetProductsBatchMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service CatalogService.
   */
  public static abstract class CatalogServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return CatalogServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service CatalogService.
   */
  public static final class CatalogServiceStub
      extends io.grpc.stub.AbstractAsyncStub<CatalogServiceStub> {
    private CatalogServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CatalogServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CatalogServiceStub(channel, callOptions);
    }

    /**
     */
    public void getProductDetails(com.instacommerce.contracts.catalog.v1.GetProductDetailsRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.catalog.v1.GetProductDetailsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetProductDetailsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getProductsBatch(com.instacommerce.contracts.catalog.v1.GetProductsBatchRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.catalog.v1.GetProductsBatchResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetProductsBatchMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service CatalogService.
   */
  public static final class CatalogServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<CatalogServiceBlockingStub> {
    private CatalogServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CatalogServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CatalogServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.instacommerce.contracts.catalog.v1.GetProductDetailsResponse getProductDetails(com.instacommerce.contracts.catalog.v1.GetProductDetailsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetProductDetailsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.instacommerce.contracts.catalog.v1.GetProductsBatchResponse getProductsBatch(com.instacommerce.contracts.catalog.v1.GetProductsBatchRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetProductsBatchMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service CatalogService.
   */
  public static final class CatalogServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<CatalogServiceFutureStub> {
    private CatalogServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CatalogServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CatalogServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.instacommerce.contracts.catalog.v1.GetProductDetailsResponse> getProductDetails(
        com.instacommerce.contracts.catalog.v1.GetProductDetailsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetProductDetailsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.instacommerce.contracts.catalog.v1.GetProductsBatchResponse> getProductsBatch(
        com.instacommerce.contracts.catalog.v1.GetProductsBatchRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetProductsBatchMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_PRODUCT_DETAILS = 0;
  private static final int METHODID_GET_PRODUCTS_BATCH = 1;

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
        case METHODID_GET_PRODUCT_DETAILS:
          serviceImpl.getProductDetails((com.instacommerce.contracts.catalog.v1.GetProductDetailsRequest) request,
              (io.grpc.stub.StreamObserver<com.instacommerce.contracts.catalog.v1.GetProductDetailsResponse>) responseObserver);
          break;
        case METHODID_GET_PRODUCTS_BATCH:
          serviceImpl.getProductsBatch((com.instacommerce.contracts.catalog.v1.GetProductsBatchRequest) request,
              (io.grpc.stub.StreamObserver<com.instacommerce.contracts.catalog.v1.GetProductsBatchResponse>) responseObserver);
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
          getGetProductDetailsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.instacommerce.contracts.catalog.v1.GetProductDetailsRequest,
              com.instacommerce.contracts.catalog.v1.GetProductDetailsResponse>(
                service, METHODID_GET_PRODUCT_DETAILS)))
        .addMethod(
          getGetProductsBatchMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.instacommerce.contracts.catalog.v1.GetProductsBatchRequest,
              com.instacommerce.contracts.catalog.v1.GetProductsBatchResponse>(
                service, METHODID_GET_PRODUCTS_BATCH)))
        .build();
  }

  private static abstract class CatalogServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    CatalogServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.instacommerce.contracts.catalog.v1.CatalogServiceOuterClass.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("CatalogService");
    }
  }

  private static final class CatalogServiceFileDescriptorSupplier
      extends CatalogServiceBaseDescriptorSupplier {
    CatalogServiceFileDescriptorSupplier() {}
  }

  private static final class CatalogServiceMethodDescriptorSupplier
      extends CatalogServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    CatalogServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (CatalogServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new CatalogServiceFileDescriptorSupplier())
              .addMethod(getGetProductDetailsMethod())
              .addMethod(getGetProductsBatchMethod())
              .build();
        }
      }
    }
    return result;
  }
}
