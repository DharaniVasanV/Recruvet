package com.fakejobpostsystem.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "institution")
public class Institution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "verified_email_domain", nullable = false, unique = true, length = 150)
    private String verifiedEmailDomain;

    @Column(name = "subscription_tier", nullable = false, length = 50)
    private String subscriptionTier = "FREE";

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVerifiedEmailDomain() {
        return verifiedEmailDomain;
    }

    public void setVerifiedEmailDomain(String verifiedEmailDomain) {
        this.verifiedEmailDomain = verifiedEmailDomain;
    }

    public String getSubscriptionTier() {
        return subscriptionTier;
    }

    public void setSubscriptionTier(String subscriptionTier) {
        this.subscriptionTier = subscriptionTier;
    }
}
