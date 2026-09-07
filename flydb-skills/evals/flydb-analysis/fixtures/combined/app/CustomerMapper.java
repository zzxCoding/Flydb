package sample;

import org.apache.ibatis.annotations.Param;

public interface CustomerMapper {
    CustomerRow find(@Param("id") long id);
    CustomerRow byColumn(@Param("column") String column, @Param("value") String value);
}
