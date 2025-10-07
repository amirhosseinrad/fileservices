// src/main/java/.../api/dto/ContractRequest.java
package ir.ipaam.fileservice.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ContractRequest {
    @NotBlank private String first_name;
    @NotBlank private String last_name;
    @NotBlank private String father_name;
    @NotBlank private String birth_date;
    @NotBlank private String birth_certificate_number;
    @NotBlank private String birth_city_name;
    @NotBlank private String serial_number;
    @NotBlank private String national_number;
    @NotBlank private String financial_code;
    @NotBlank private String address;
    @NotBlank private String postal_code;
    @NotBlank private String landline_number;
    @NotBlank private String mobile_number;
    @JsonAlias("eamil")
    @NotBlank private String email;
    @NotBlank private String sign_date;
    @NotBlank private String financial_facilities_value;
    @NotBlank private String financial_facilities_value_in_alphabet;
    @NotBlank private String financial_facilities_main_value;
    @NotBlank private String financial_facilities_main_value_in_alphabet;
    @NotBlank private String profit_percent;
    @NotBlank private String profit_value;
    @NotBlank private String profit_value_in_alphabet;
    @NotBlank private String advance_rent;
    @NotBlank private String advance_rent_in_alphabet;
    @NotBlank private String profit_and_financial_facilities_value;
    @NotBlank private String profit_and_financial_facilities_value_in_alphabet;
    @NotBlank private String payment_method;
    @NotBlank private String financial_facilities_months;
    @NotBlank private String deposit_number;
    @NotBlank private String contract_date;
    @NotBlank private String pdf_code;
}
