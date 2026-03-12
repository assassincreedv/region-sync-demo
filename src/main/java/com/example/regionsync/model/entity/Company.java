package com.example.regionsync.model.entity;

import com.example.regionsync.model.base.SyncableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.Builder.Default;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "companies")
public class Company extends SyncableEntity {

    @Column(name = "company_code", length = 100, unique = true)
    private String companyCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Default
    @Column(name = "status", length = 20)
    private String status = "ACTIVE";
}
