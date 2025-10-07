// src/main/java/.../api/mapper/ContractModelMapper.java
package ir.ipaam.fileservice.api.mapper;

import ir.ipaam.fileservice.api.dto.ContractRequest;

import java.util.HashMap;
import java.util.Map;

public final class ContractModelMapper {
    private ContractModelMapper() {}
    public static Map<String,Object> toModel(ContractRequest r) {
        Map<String,Object> m = new HashMap<>(64);
        m.put("first_name", r.getFirst_name());
        m.put("last_name", r.getLast_name());
        m.put("father_name", r.getFather_name());
        m.put("birth_date", r.getBirth_date());
        m.put("birth_certificate_number", r.getBirth_certificate_number());
        m.put("birth_city_name", r.getBirth_city_name());
        m.put("serial_number", r.getSerial_number());
        m.put("national_number", r.getNational_number());
        m.put("financial_code", r.getFinancial_code());
        m.put("address", r.getAddress());
        m.put("postal_code", r.getPostal_code());
        m.put("landline_number", r.getLandline_number());
        m.put("mobile_number", r.getMobile_number());
        m.put("eamil", r.getEmail());
        m.put("sign_date", r.getSign_date());
        m.put("financial_facilities_value", r.getFinancial_facilities_value());
        m.put("financial_facilities_value_in_alphabet", r.getFinancial_facilities_value_in_alphabet());
        m.put("financial_facilities_main_value", r.getFinancial_facilities_main_value());
        m.put("financial_facilities_main_value_in_alphabet", r.getFinancial_facilities_main_value_in_alphabet());
        m.put("profit_percent", r.getProfit_percent());
        m.put("profit_value", r.getProfit_value());
        m.put("profit_value_in_alphabet", r.getProfit_value_in_alphabet());
        m.put("advance_rent", r.getAdvance_rent());
        m.put("advance_rent_in_alphabet", r.getAdvance_rent_in_alphabet());
        m.put("profit_and_financial_facilities_value", r.getProfit_and_financial_facilities_value());
        m.put("profit_and_financial_facilities_value_in_alphabet", r.getProfit_and_financial_facilities_value_in_alphabet());
        m.put("payment_method", r.getPayment_method());
        m.put("financial_facilities_months", r.getFinancial_facilities_months());
        m.put("deposit_number", r.getDeposit_number());
        m.put("contract_date", r.getContract_date());
        m.put("pdf_code", r.getPdf_code());
        return m;
    }
}
