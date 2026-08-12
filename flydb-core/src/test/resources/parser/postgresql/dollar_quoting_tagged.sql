CREATE FUNCTION f() RETURNS void AS $body$
BEGIN
  RAISE NOTICE 'has $$ and ; inside';
END;
$body$ LANGUAGE plpgsql;
