-- Demo schema for the Forms ↔ Vaadin hybrid: one HYBRID user owning a small
-- CUSTOMERS table with fictional data. Run as SYS (or SYSTEM) in the pluggable
-- database the form connects to. Re-running drops and recreates everything.

WHENEVER SQLERROR EXIT FAILURE

BEGIN
  EXECUTE IMMEDIATE 'DROP USER hybrid CASCADE';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -1918 THEN RAISE; END IF;  -- ORA-01918: user does not exist
END;
/

CREATE USER hybrid IDENTIFIED BY hybrid QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE TO hybrid;

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
COMMIT;

EXIT
