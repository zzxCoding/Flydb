CREATE OR REPLACE TRIGGER trg_before_insert
BEFORE INSERT ON t
FOR EACH ROW
BEGIN
  :NEW.id := seq.nextval;
END;
/
