-- Demo schema for the Forms ↔ Vaadin hybrid: one HYBRID user owning a small
-- CUSTOMERS table with fictional data. Run as SYS (or SYSTEM) in the pluggable
-- database the form connects to. Re-running drops and recreates everything.

WHENEVER SQLERROR EXIT FAILURE

-- Disconnect running demo sessions (the form, the view's connection pool),
-- or DROP USER fails with ORA-01940.
BEGIN
  FOR s IN (SELECT sid, serial# AS serial FROM v$session WHERE username = 'HYBRID') LOOP
    EXECUTE IMMEDIATE 'ALTER SYSTEM KILL SESSION ''' || s.sid || ',' || s.serial || ''' IMMEDIATE';
  END LOOP;
END;
/

-- Killed sessions take a moment to go away: retry the drop for up to 30 s.
DECLARE
  user_connected EXCEPTION;
  PRAGMA EXCEPTION_INIT(user_connected, -1940);
  user_missing EXCEPTION;
  PRAGMA EXCEPTION_INIT(user_missing, -1918);
BEGIN
  FOR attempt IN 1 .. 30 LOOP
    BEGIN
      EXECUTE IMMEDIATE 'DROP USER hybrid CASCADE';
      EXIT;
    EXCEPTION
      WHEN user_missing THEN EXIT;
      WHEN user_connected THEN
        IF attempt = 30 THEN RAISE; END IF;
        DBMS_SESSION.SLEEP(1);
    END;
  END LOOP;
END;
/

CREATE USER hybrid IDENTIFIED BY hybrid QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW TO hybrid;

CREATE TABLE hybrid.customers (
  id      NUMBER(6)     PRIMARY KEY,
  name    VARCHAR2(60)  NOT NULL,
  city    VARCHAR2(40),
  country VARCHAR2(40)
);

INSERT INTO hybrid.customers VALUES (101, 'Northwind Bicycles',   'Turku',      'Finland');
INSERT INTO hybrid.customers VALUES (102, 'Harbour Lights Cafe',  'Lisbon',     'Portugal');
INSERT INTO hybrid.customers VALUES (103, 'Blue Fjord Outfitters','Bergen',     'Norway');
INSERT INTO hybrid.customers VALUES (104, 'Maple Leaf Hardware',  'Halifax',    'Canada');
INSERT INTO hybrid.customers VALUES (105, 'Sunrise Bakery',       'Valencia',   'Spain');
INSERT INTO hybrid.customers VALUES (106, 'Granite Peak Climbing','Innsbruck',  'Austria');
INSERT INTO hybrid.customers VALUES (107, 'Tidewater Books',      'Galway',     'Ireland');
INSERT INTO hybrid.customers VALUES (108, 'Copper Kettle Foods',  'Tartu',      'Estonia');
INSERT INTO hybrid.customers VALUES (109, 'Lantern Street Games', 'Kyoto',      'Japan');
INSERT INTO hybrid.customers VALUES (110, 'Riverbend Garden Co.', 'Ghent',      'Belgium');
INSERT INTO hybrid.customers VALUES (111, 'Polar Star Optics',    'Tromsø',     'Norway');
INSERT INTO hybrid.customers VALUES (112, 'Old Mill Textiles',    'Łódź',       'Poland');

CREATE TABLE hybrid.products (
  id    NUMBER(6)    PRIMARY KEY,
  name  VARCHAR2(60) NOT NULL,
  price NUMBER(9,2)  NOT NULL
);

INSERT INTO hybrid.products VALUES (1, 'Trail backpack 30 L',  89.00);
INSERT INTO hybrid.products VALUES (2, 'Insulated bottle',     24.50);
INSERT INTO hybrid.products VALUES (3, 'Headlamp',             39.90);
INSERT INTO hybrid.products VALUES (4, 'Wool socks (3 pairs)', 29.00);
INSERT INTO hybrid.products VALUES (5, 'Rain jacket',         149.00);
INSERT INTO hybrid.products VALUES (6, 'Camp stove',           64.00);

-- Written only by the Vaadin view (ADR-0003). VERSION is bumped on every
-- update, so a save can tell when the row changed since it was read.
CREATE TABLE hybrid.orders (
  id          NUMBER(8)    PRIMARY KEY,
  customer_id NUMBER(6)    NOT NULL REFERENCES hybrid.customers,
  order_date  DATE         NOT NULL,
  product_id  NUMBER(6)    NOT NULL REFERENCES hybrid.products,
  quantity    NUMBER(5)    NOT NULL CHECK (quantity > 0),
  status      VARCHAR2(10) NOT NULL CHECK (status IN ('NEW', 'PAID', 'SHIPPED', 'CANCELLED')),
  version     NUMBER(9)    DEFAULT 0 NOT NULL
);
CREATE INDEX hybrid.orders_customer ON hybrid.orders (customer_id);

INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1001, 103, DATE '2026-09-02', 1, 4, 'SHIPPED');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1002, 103, DATE '2026-09-20', 5, 2, 'PAID');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1003, 103, DATE '2026-10-14', 3, 10, 'NEW');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1004, 101, DATE '2026-08-11', 6, 3, 'SHIPPED');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1005, 101, DATE '2026-10-01', 2, 12, 'NEW');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1006, 102, DATE '2026-09-15', 2, 30, 'PAID');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1007, 104, DATE '2026-07-30', 4, 20, 'SHIPPED');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1008, 104, DATE '2026-10-19', 5, 5, 'NEW');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1009, 106, DATE '2026-09-08', 3, 15, 'PAID');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1010, 106, DATE '2026-10-05', 1, 6, 'NEW');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1011, 108, DATE '2026-10-21', 6, 2, 'NEW');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1012, 109, DATE '2026-09-27', 4, 8, 'CANCELLED');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1013, 111, DATE '2026-08-24', 5, 3, 'SHIPPED');
INSERT INTO hybrid.orders (id, customer_id, order_date, product_id, quantity, status) VALUES (1014, 112, DATE '2026-10-09', 2, 18, 'PAID');

-- The one definition of a customer's orders total, used by both the form and
-- the view: the value of all orders that aren't cancelled.
CREATE VIEW hybrid.order_totals AS
  SELECT o.customer_id, SUM(o.quantity * p.price) AS total
  FROM hybrid.orders o JOIN hybrid.products p ON p.id = o.product_id
  WHERE o.status <> 'CANCELLED'
  GROUP BY o.customer_id;
COMMIT;

EXIT
