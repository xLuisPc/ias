-- Datos semilla. Se insertan solo si no existen para no reiniciar el cupo consumido en cada arranque.
INSERT INTO customer (id, status, credit_limit, approved_amount)
SELECT 'CLI-1001', 'ELIGIBLE', 10000000, 0 WHERE NOT EXISTS (SELECT 1 FROM customer WHERE id = 'CLI-1001');

INSERT INTO customer (id, status, credit_limit, approved_amount)
SELECT 'CLI-1002', 'BLOCKED', 8000000, 0 WHERE NOT EXISTS (SELECT 1 FROM customer WHERE id = 'CLI-1002');

INSERT INTO customer (id, status, credit_limit, approved_amount)
SELECT 'CLI-2001', 'ELIGIBLE', 15000000, 0 WHERE NOT EXISTS (SELECT 1 FROM customer WHERE id = 'CLI-2001');
