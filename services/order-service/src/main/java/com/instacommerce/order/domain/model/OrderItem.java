package com.instacommerce.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "product_sku", nullable = false)
    private String productSku;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    @Column(name = "line_total_cents", nullable = false)
    private long lineTotalCents;

    @Column(name = "picked_status")
    private String pickedStatus = "PENDING";

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getProductSku() {
        return productSku;
    }

    public void setProductSku(String productSku) {
        this.productSku = productSku;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public long getUnitPriceCents() {
        return unitPriceCents;
    }

    public void setUnitPriceCents(long unitPriceCents) {
        this.unitPriceCents = unitPriceCents;
    }

    public long getLineTotalCents() {
        return lineTotalCents;
    }

    public void setLineTotalCents(long lineTotalCents) {
        this.lineTotalCents = lineTotalCents;
    }

    public String getPickedStatus() {
        return pickedStatus;
    }

    public void setPickedStatus(String pickedStatus) {
        this.pickedStatus = pickedStatus;
    }
}
