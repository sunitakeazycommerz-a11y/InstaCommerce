package com.instacommerce.contracts.inventory.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.62.2)",
    comments = "Source: inventory/v1/inventory_service.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class InventoryServiceGrpc {

  private InventoryServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "inventory.v1.InventoryService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.instacommerce.contracts.inventory.v1.ReserveStockRequest,
      com.instacommerce.contracts.inventory.v1.ReserveStockResponse> getReserveStockMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReserveStock",
      requestType = com.instacommerce.contracts.inventory.v1.ReserveStockRequest.class,
      responseType = com.instacommerce.contracts.inventory.v1.ReserveStockResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.instacommerce.contracts.inventory.v1.ReserveStockRequest,
      com.instacommerce.contracts.inventory.v1.ReserveStockResponse> getReserveStockMethod() {
    io.grpc.MethodDescriptor<com.instacommerce.contracts.inventory.v1.ReserveStockRequest, com.instacommerce.contracts.inventory.v1.ReserveStockResponse> getReserveStockMethod;
    if ((getReserveStockMethod = InventoryServiceGrpc.getReserveStockMethod) == null) {
      synchronized (InventoryServiceGrpc.class) {
        if ((getReserveStockMethod = InventoryServiceGrpc.getReserveStockMethod) == null) {
          InventoryServiceGrpc.getReserveStockMethod = getReserveStockMethod =
              io.grpc.MethodDescriptor.<com.instacommerce.contracts.inventory.v1.ReserveStockRequest, com.instacommerce.contracts.inventory.v1.ReserveStockResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReserveStock"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.inventory.v1.ReserveStockRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.inventory.v1.ReserveStockResponse.getDefaultInstance()))
              .setSchemaDescriptor(new InventoryServiceMethodDescriptorSupplier("ReserveStock"))
              .build();
        }
      }
    }
    return getReserveStockMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.instacommerce.contracts.inventory.v1.ConfirmReservationRequest,
      com.instacommerce.contracts.inventory.v1.ConfirmReservationResponse> getConfirmReservationMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ConfirmReservation",
      requestType = com.instacommerce.contracts.inventory.v1.ConfirmReservationRequest.class,
      responseType = com.instacommerce.contracts.inventory.v1.ConfirmReservationResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.instacommerce.contracts.inventory.v1.ConfirmReservationRequest,
      com.instacommerce.contracts.inventory.v1.ConfirmReservationResponse> getConfirmReservationMethod() {
    io.grpc.MethodDescriptor<com.instacommerce.contracts.inventory.v1.ConfirmReservationRequest, com.instacommerce.contracts.inventory.v1.ConfirmReservationResponse> getConfirmReservationMethod;
    if ((getConfirmReservationMethod = InventoryServiceGrpc.getConfirmReservationMethod) == null) {
      synchronized (InventoryServiceGrpc.class) {
        if ((getConfirmReservationMethod = InventoryServiceGrpc.getConfirmReservationMethod) == null) {
          InventoryServiceGrpc.getConfirmReservationMethod = getConfirmReservationMethod =
              io.grpc.MethodDescriptor.<com.instacommerce.contracts.inventory.v1.ConfirmReservationRequest, com.instacommerce.contracts.inventory.v1.ConfirmReservationResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ConfirmReservation"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.inventory.v1.ConfirmReservationRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.inventory.v1.ConfirmReservationResponse.getDefaultInstance()))
              .setSchemaDescriptor(new InventoryServiceMethodDescriptorSupplier("ConfirmReservation"))
              .build();
        }
      }
    }
    return getConfirmReservationMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.instacommerce.contracts.inventory.v1.CancelReservationRequest,
      com.instacommerce.contracts.inventory.v1.CancelReservationResponse> getCancelReservationMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CancelReservation",
      requestType = com.instacommerce.contracts.inventory.v1.CancelReservationRequest.class,
      responseType = com.instacommerce.contracts.inventory.v1.CancelReservationResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.instacommerce.contracts.inventory.v1.CancelReservationRequest,
      com.instacommerce.contracts.inventory.v1.CancelReservationResponse> getCancelReservationMethod() {
    io.grpc.MethodDescriptor<com.instacommerce.contracts.inventory.v1.CancelReservationRequest, com.instacommerce.contracts.inventory.v1.CancelReservationResponse> getCancelReservationMethod;
    if ((getCancelReservationMethod = InventoryServiceGrpc.getCancelReservationMethod) == null) {
      synchronized (InventoryServiceGrpc.class) {
        if ((getCancelReservationMethod = InventoryServiceGrpc.getCancelReservationMethod) == null) {
          InventoryServiceGrpc.getCancelReservationMethod = getCancelReservationMethod =
              io.grpc.MethodDescriptor.<com.instacommerce.contracts.inventory.v1.CancelReservationRequest, com.instacommerce.contracts.inventory.v1.CancelReservationResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CancelReservation"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.inventory.v1.CancelReservationRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.inventory.v1.CancelReservationResponse.getDefaultInstance()))
              .setSchemaDescriptor(new InventoryServiceMethodDescriptorSupplier("CancelReservation"))
              .build();
        }
      }
    }
    return getCancelReservationMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.instacommerce.contracts.inventory.v1.CheckAvailabilityRequest,
      com.instacommerce.contracts.inventory.v1.CheckAvailabilityResponse> getCheckAvailabilityMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CheckAvailability",
      requestType = com.instacommerce.contracts.inventory.v1.CheckAvailabilityRequest.class,
      responseType = com.instacommerce.contracts.inventory.v1.CheckAvailabilityResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.instacommerce.contracts.inventory.v1.CheckAvailabilityRequest,
      com.instacommerce.contracts.inventory.v1.CheckAvailabilityResponse> getCheckAvailabilityMethod() {
    io.grpc.MethodDescriptor<com.instacommerce.contracts.inventory.v1.CheckAvailabilityRequest, com.instacommerce.contracts.inventory.v1.CheckAvailabilityResponse> getCheckAvailabilityMethod;
    if ((getCheckAvailabilityMethod = InventoryServiceGrpc.getCheckAvailabilityMethod) == null) {
      synchronized (InventoryServiceGrpc.class) {
        if ((getCheckAvailabilityMethod = InventoryServiceGrpc.getCheckAvailabilityMethod) == null) {
          InventoryServiceGrpc.getCheckAvailabilityMethod = getCheckAvailabilityMethod =
              io.grpc.MethodDescriptor.<com.instacommerce.contracts.inventory.v1.CheckAvailabilityRequest, com.instacommerce.contracts.inventory.v1.CheckAvailabilityResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CheckAvailability"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.inventory.v1.CheckAvailabilityRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.instacommerce.contracts.inventory.v1.CheckAvailabilityResponse.getDefaultInstance()))
              .setSchemaDescriptor(new InventoryServiceMethodDescriptorSupplier("CheckAvailability"))
              .build();
        }
      }
    }
    return getCheckAvailabilityMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static InventoryServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<InventoryServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<InventoryServiceStub>() {
        @java.lang.Override
        public InventoryServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new InventoryServiceStub(channel, callOptions);
        }
      };
    return InventoryServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static InventoryServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<InventoryServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<InventoryServiceBlockingStub>() {
        @java.lang.Override
        public InventoryServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new InventoryServiceBlockingStub(channel, callOptions);
        }
      };
    return InventoryServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static InventoryServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<InventoryServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<InventoryServiceFutureStub>() {
        @java.lang.Override
        public InventoryServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new InventoryServiceFutureStub(channel, callOptions);
        }
      };
    return InventoryServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void reserveStock(com.instacommerce.contracts.inventory.v1.ReserveStockRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.inventory.v1.ReserveStockResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReserveStockMethod(), responseObserver);
    }

    /**
     */
    default void confirmReservation(com.instacommerce.contracts.inventory.v1.ConfirmReservationRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.inventory.v1.ConfirmReservationResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getConfirmReservationMethod(), responseObserver);
    }

    /**
     */
    default void cancelReservation(com.instacommerce.contracts.inventory.v1.CancelReservationRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.inventory.v1.CancelReservationResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCancelReservationMethod(), responseObserver);
    }

    /**
     */
    default void checkAvailability(com.instacommerce.contracts.inventory.v1.CheckAvailabilityRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.inventory.v1.CheckAvailabilityResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCheckAvailabilityMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service InventoryService.
   */
  public static abstract class InventoryServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return InventoryServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service InventoryService.
   */
  public static final class InventoryServiceStub
      extends io.grpc.stub.AbstractAsyncStub<InventoryServiceStub> {
    private InventoryServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected InventoryServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new InventoryServiceStub(channel, callOptions);
    }

    /**
     */
    public void reserveStock(com.instacommerce.contracts.inventory.v1.ReserveStockRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.inventory.v1.ReserveStockResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReserveStockMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void confirmReservation(com.instacommerce.contracts.inventory.v1.ConfirmReservationRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.inventory.v1.ConfirmReservationResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getConfirmReservationMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void cancelReservation(com.instacommerce.contracts.inventory.v1.CancelReservationRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.inventory.v1.CancelReservationResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCancelReservationMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void checkAvailability(com.instacommerce.contracts.inventory.v1.CheckAvailabilityRequest request,
        io.grpc.stub.StreamObserver<com.instacommerce.contracts.inventory.v1.CheckAvailabilityResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCheckAvailabilityMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service InventoryService.
   */
  public static final class InventoryServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<InventoryServiceBlockingStub> {
    private InventoryServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected InventoryServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new InventoryServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.instacommerce.contracts.inventory.v1.ReserveStockResponse reserveStock(com.instacommerce.contracts.inventory.v1.ReserveStockRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReserveStockMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.instacommerce.contracts.inventory.v1.ConfirmReservationResponse confirmReservation(com.instacommerce.contracts.inventory.v1.ConfirmReservationRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getConfirmReservationMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.instacommerce.contracts.inventory.v1.CancelReservationResponse cancelReservation(com.instacommerce.contracts.inventory.v1.CancelReservationRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCancelReservationMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.instacommerce.contracts.inventory.v1.CheckAvailabilityResponse checkAvailability(com.instacommerce.contracts.inventory.v1.CheckAvailabilityRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCheckAvailabilityMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service InventoryService.
   */
  public static final class InventoryServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<InventoryServiceFutureStub> {
    private InventoryServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected InventoryServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new InventoryServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.instacommerce.contracts.inventory.v1.ReserveStockResponse> reserveStock(
        com.instacommerce.contracts.inventory.v1.ReserveStockRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReserveStockMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.instacommerce.contracts.inventory.v1.ConfirmReservationResponse> confirmReservation(
        com.instacommerce.contracts.inventory.v1.ConfirmReservationRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getConfirmReservationMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.instacommerce.contracts.inventory.v1.CancelReservationResponse> cancelReservation(
        com.instacommerce.contracts.inventory.v1.CancelReservationRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCancelReservationMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.instacommerce.contracts.inventory.v1.CheckAvailabilityResponse> checkAvailability(
        com.instacommerce.contracts.inventory.v1.CheckAvailabilityRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCheckAvailabilityMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_RESERVE_STOCK = 0;
  private static final int METHODID_CONFIRM_RESERVATION = 1;
  private static final int METHODID_CANCEL_RESERVATION = 2;
  private static final int METHODID_CHECK_AVAILABILITY = 3;

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
        case METHODID_RESERVE_STOCK:
          serviceImpl.reserveStock((com.instacommerce.contracts.inventory.v1.ReserveStockRequest) request,
              (io.grpc.stub.StreamObserver<com.instacommerce.contracts.inventory.v1.ReserveStockResponse>) responseObserver);
          break;
        case METHODID_CONFIRM_RESERVATION:
          serviceImpl.confirmReservation((com.instacommerce.contracts.inventory.v1.ConfirmReservationRequest) request,
              (io.grpc.stub.StreamObserver<com.instacommerce.contracts.inventory.v1.ConfirmReservationResponse>) responseObserver);
          break;
        case METHODID_CANCEL_RESERVATION:
          serviceImpl.cancelReservation((com.instacommerce.contracts.inventory.v1.CancelReservationRequest) request,
              (io.grpc.stub.StreamObserver<com.instacommerce.contracts.inventory.v1.CancelReservationResponse>) responseObserver);
          break;
        case METHODID_CHECK_AVAILABILITY:
          serviceImpl.checkAvailability((com.instacommerce.contracts.inventory.v1.CheckAvailabilityRequest) request,
              (io.grpc.stub.StreamObserver<com.instacommerce.contracts.inventory.v1.CheckAvailabilityResponse>) responseObserver);
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
          getReserveStockMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.instacommerce.contracts.inventory.v1.ReserveStockRequest,
              com.instacommerce.contracts.inventory.v1.ReserveStockResponse>(
                service, METHODID_RESERVE_STOCK)))
        .addMethod(
          getConfirmReservationMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.instacommerce.contracts.inventory.v1.ConfirmReservationRequest,
              com.instacommerce.contracts.inventory.v1.ConfirmReservationResponse>(
                service, METHODID_CONFIRM_RESERVATION)))
        .addMethod(
          getCancelReservationMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.instacommerce.contracts.inventory.v1.CancelReservationRequest,
              com.instacommerce.contracts.inventory.v1.CancelReservationResponse>(
                service, METHODID_CANCEL_RESERVATION)))
        .addMethod(
          getCheckAvailabilityMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.instacommerce.contracts.inventory.v1.CheckAvailabilityRequest,
              com.instacommerce.contracts.inventory.v1.CheckAvailabilityResponse>(
                service, METHODID_CHECK_AVAILABILITY)))
        .build();
  }

  private static abstract class InventoryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    InventoryServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.instacommerce.contracts.inventory.v1.InventoryServiceOuterClass.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("InventoryService");
    }
  }

  private static final class InventoryServiceFileDescriptorSupplier
      extends InventoryServiceBaseDescriptorSupplier {
    InventoryServiceFileDescriptorSupplier() {}
  }

  private static final class InventoryServiceMethodDescriptorSupplier
      extends InventoryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    InventoryServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (InventoryServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new InventoryServiceFileDescriptorSupplier())
              .addMethod(getReserveStockMethod())
              .addMethod(getConfirmReservationMethod())
              .addMethod(getCancelReservationMethod())
              .addMethod(getCheckAvailabilityMethod())
              .build();
        }
      }
    }
    return result;
  }
}
