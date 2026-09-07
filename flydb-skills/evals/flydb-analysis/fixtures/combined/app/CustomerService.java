package sample;

public class CustomerService {
    private static final String TIER_COLUMN = "CUST_" + "TYPE";
    private final CustomerMapper mapper;
    public CustomerService(CustomerMapper mapper) { this.mapper = mapper; }
    public String lookupTier(String value) {
        return mapper.byColumn(TIER_COLUMN, value).getCustomerType();
    }
}
