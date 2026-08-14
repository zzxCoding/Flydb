CREATE OR REPLACE FUNCTION add_one(n IN NUMBER) RETURN NUMBER IS
  v_result NUMBER;
BEGIN
  v_result := n + 1;
  RETURN v_result;
END;
/
