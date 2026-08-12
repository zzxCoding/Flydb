CREATE TABLE boot2_flydb_demo (
    id INT PRIMARY KEY,
    marker VARCHAR(100) NOT NULL
);

INSERT INTO boot2_flydb_demo (id, marker) VALUES (1, 'migration-applied');
