package com.h4ckinto.android.model;

import java.util.List;

public class GrantApplication {
    private long id;
    private String fullName;
    private String idNumber;
    private String phoneNumber;
    private String email;
    private String propertyAddress;
    private int familyMembers;
    private double monthlyIncome;
    private String damageLevel;
    private String propertyType;
    private double propertyArea;
    private String propertyOwnership;
    private String damageDate;
    private String damageDescription;
    private double propertyLatitude;
    private double propertyLongitude;
    private String photosJson; // JSON array of photo paths
    private String createdAt;
    private String status; // PENDING, APPROVED, REJECTED

    // Constructors
    public GrantApplication() {}

    public GrantApplication(String fullName, String idNumber, String phoneNumber, String email,
                          String propertyAddress, int familyMembers, double monthlyIncome,
                          String damageLevel, String propertyType, double propertyArea,
                          String propertyOwnership, String damageDate, String damageDescription) {
        this.fullName = fullName;
        this.idNumber = idNumber;
        this.phoneNumber = phoneNumber;
        this.email = email;
        this.propertyAddress = propertyAddress;
        this.familyMembers = familyMembers;
        this.monthlyIncome = monthlyIncome;
        this.damageLevel = damageLevel;
        this.propertyType = propertyType;
        this.propertyArea = propertyArea;
        this.propertyOwnership = propertyOwnership;
        this.damageDate = damageDate;
        this.damageDescription = damageDescription;
        this.status = "PENDING";
    }

    // Getters and Setters
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getIdNumber() {
        return idNumber;
    }

    public void setIdNumber(String idNumber) {
        this.idNumber = idNumber;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPropertyAddress() {
        return propertyAddress;
    }

    public void setPropertyAddress(String propertyAddress) {
        this.propertyAddress = propertyAddress;
    }

    public int getFamilyMembers() {
        return familyMembers;
    }

    public void setFamilyMembers(int familyMembers) {
        this.familyMembers = familyMembers;
    }

    public double getMonthlyIncome() {
        return monthlyIncome;
    }

    public void setMonthlyIncome(double monthlyIncome) {
        this.monthlyIncome = monthlyIncome;
    }

    public String getDamageLevel() {
        return damageLevel;
    }

    public void setDamageLevel(String damageLevel) {
        this.damageLevel = damageLevel;
    }

    public String getPropertyType() {
        return propertyType;
    }

    public void setPropertyType(String propertyType) {
        this.propertyType = propertyType;
    }

    public double getPropertyArea() {
        return propertyArea;
    }

    public void setPropertyArea(double propertyArea) {
        this.propertyArea = propertyArea;
    }

    public String getPropertyOwnership() {
        return propertyOwnership;
    }

    public void setPropertyOwnership(String propertyOwnership) {
        this.propertyOwnership = propertyOwnership;
    }

    public String getDamageDate() {
        return damageDate;
    }

    public void setDamageDate(String damageDate) {
        this.damageDate = damageDate;
    }

    public String getDamageDescription() {
        return damageDescription;
    }

    public void setDamageDescription(String damageDescription) {
        this.damageDescription = damageDescription;
    }

    public double getPropertyLatitude() {
        return propertyLatitude;
    }

    public void setPropertyLatitude(double propertyLatitude) {
        this.propertyLatitude = propertyLatitude;
    }

    public double getPropertyLongitude() {
        return propertyLongitude;
    }

    public void setPropertyLongitude(double propertyLongitude) {
        this.propertyLongitude = propertyLongitude;
    }

    public String getPhotosJson() {
        return photosJson;
    }

    public void setPhotosJson(String photosJson) {
        this.photosJson = photosJson;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    // Helper methods
    public boolean isValid() {
        return fullName != null && !fullName.trim().isEmpty() &&
               idNumber != null && !idNumber.trim().isEmpty() &&
               phoneNumber != null && !phoneNumber.trim().isEmpty() &&
               propertyAddress != null && !propertyAddress.trim().isEmpty() &&
               familyMembers > 0 &&
               monthlyIncome >= 0 &&
               damageLevel != null && !damageLevel.trim().isEmpty() &&
               propertyType != null && !propertyType.trim().isEmpty() &&
               propertyArea > 0 &&
               propertyOwnership != null && !propertyOwnership.trim().isEmpty() &&
               damageDate != null && !damageDate.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "GrantApplication{" +
                "id=" + id +
                ", fullName='" + fullName + '\'' +
                ", idNumber='" + idNumber + '\'' +
                ", phoneNumber='" + phoneNumber + '\'' +
                ", email='" + email + '\'' +
                ", propertyAddress='" + propertyAddress + '\'' +
                ", familyMembers=" + familyMembers +
                ", monthlyIncome=" + monthlyIncome +
                ", damageLevel='" + damageLevel + '\'' +
                ", propertyType='" + propertyType + '\'' +
                ", propertyArea=" + propertyArea +
                ", propertyOwnership='" + propertyOwnership + '\'' +
                ", damageDate='" + damageDate + '\'' +
                ", damageDescription='" + damageDescription + '\'' +
                ", propertyLatitude=" + propertyLatitude +
                ", propertyLongitude=" + propertyLongitude +
                ", photosJson='" + photosJson + '\'' +
                ", createdAt='" + createdAt + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
