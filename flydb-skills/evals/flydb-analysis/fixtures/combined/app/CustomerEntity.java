package sample;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "CUSTOMER", schema = "SALES")
public class CustomerEntity {
    @Id private Long id;
    @Column(name = "CUST_TYPE") private String customerType;
}
