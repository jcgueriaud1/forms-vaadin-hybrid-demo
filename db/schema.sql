-- Demo schema for the Forms ↔ Vaadin hybrid: one HYBRID user owning small
-- tables with fictional data, and two Forms users, ALICE and BOB, for the
-- identity showcase (#17). Run as SYS in the pluggable database the forms
-- connect to. Re-running drops and recreates everything.

WHENEVER SQLERROR EXIT FAILURE

-- Disconnect running demo sessions (the forms, the view's connection pool),
-- or DROP USER fails with ORA-01940. Lock the accounts first: a running view's
-- connection pool reconnects as soon as its sessions are killed.
BEGIN
  FOR u IN (SELECT username FROM dba_users WHERE username IN ('HYBRID', 'ALICE', 'BOB')) LOOP
    EXECUTE IMMEDIATE 'ALTER USER ' || u.username || ' ACCOUNT LOCK';
  END LOOP;
  FOR s IN (SELECT sid, serial# AS serial FROM v$session
            WHERE username IN ('HYBRID', 'ALICE', 'BOB')) LOOP
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
  FOR u IN (SELECT column_value AS name FROM TABLE(sys.odcivarchar2list('ALICE', 'BOB', 'HYBRID'))) LOOP
    FOR attempt IN 1 .. 30 LOOP
      BEGIN
        EXECUTE IMMEDIATE 'DROP USER ' || u.name || ' CASCADE';
        EXIT;
      EXCEPTION
        WHEN user_missing THEN EXIT;
        WHEN user_connected THEN
          IF attempt = 30 THEN RAISE; END IF;
          DBMS_SESSION.SLEEP(1);
      END;
    END LOOP;
  END LOOP;
END;
/

CREATE USER hybrid IDENTIFIED BY hybrid QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE PROCEDURE, CREATE TRIGGER,
      CREATE SEQUENCE TO hybrid;
-- issue_token draws its tokens from DBMS_CRYPTO
GRANT EXECUTE ON sys.dbms_crypto TO hybrid;

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

-- Identity (#17, ADR-0003). The view must know which Forms user it serves
-- without trusting anything the client sends. So the form asks the database
-- for a one-time token, which the database ties to the session's own user, and
-- the bean carries only the token. The view's server redeems it once.
CREATE TABLE hybrid.hybrid_tokens (
  token      VARCHAR2(64)  PRIMARY KEY,
  forms_user VARCHAR2(128) NOT NULL,
  -- with its time zone: compared with SYSTIMESTAMP from sessions in any zone
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Runs with HYBRID's rights, so the Forms users can issue tokens without any
-- access to the table, and only for themselves: the user is SESSION_USER, the
-- user the calling session logged in as, never a parameter.
CREATE FUNCTION hybrid.issue_token RETURN VARCHAR2 AS
  PRAGMA AUTONOMOUS_TRANSACTION; -- commit the token, not the caller's work
  token VARCHAR2(64) := RAWTOHEX(DBMS_CRYPTO.RANDOMBYTES(32));
BEGIN
  DELETE FROM hybrid.hybrid_tokens WHERE expires_at < SYSTIMESTAMP;
  INSERT INTO hybrid.hybrid_tokens
    VALUES (token, SYS_CONTEXT('USERENV', 'SESSION_USER'), SYSTIMESTAMP + INTERVAL '60' SECOND);
  COMMIT;
  RETURN token;
END;
/

-- Written only by the Vaadin view. The author isn't a value the view sends:
-- the trigger takes it from the client identifier, which the view's server
-- sets from the redeemed token for the duration of the transaction.
CREATE TABLE hybrid.customer_notes (
  id          NUMBER        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  customer_id NUMBER(6)     NOT NULL REFERENCES hybrid.customers,
  note        VARCHAR2(400) NOT NULL,
  author      VARCHAR2(128) NOT NULL,
  -- read back in each session's time zone, so users see their local time
  created_at  TIMESTAMP WITH LOCAL TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX hybrid.customer_notes_customer ON hybrid.customer_notes (customer_id);

CREATE TRIGGER hybrid.customer_notes_author
  BEFORE INSERT ON hybrid.customer_notes FOR EACH ROW
BEGIN
  :new.author := SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');
  IF :new.author IS NULL THEN
    RAISE_APPLICATION_ERROR(-20001, 'No signed-in Forms user: notes need one');
  END IF;
END;
/

BEGIN
  DBMS_SESSION.SET_IDENTIFIER('ALICE');
  INSERT INTO hybrid.customer_notes (customer_id, note, created_at)
    VALUES (103, 'Wants the new rain jackets before the winter season.', TIMESTAMP '2026-10-20 09:15:00');
  INSERT INTO hybrid.customer_notes (customer_id, note, created_at)
    VALUES (106, 'Asked for a volume discount on headlamps.', TIMESTAMP '2026-10-22 14:02:00');
  DBMS_SESSION.SET_IDENTIFIER('BOB');
  INSERT INTO hybrid.customer_notes (customer_id, note, created_at)
    VALUES (103, 'Prefers delivery on Tuesdays.', TIMESTAMP '2026-10-23 11:40:00');
  DBMS_SESSION.CLEAR_IDENTIFIER;
END;
/

-- The Forms users. Each logs in to Forms as itself, reads customers and the
-- orders totals (for the customers form, opened from the Forms menu) through
-- synonyms, and may issue tokens; nothing else.
BEGIN
  FOR u IN (SELECT column_value AS name FROM TABLE(sys.odcivarchar2list('ALICE', 'BOB'))) LOOP
    EXECUTE IMMEDIATE 'CREATE USER ' || u.name || ' IDENTIFIED BY ' || LOWER(u.name);
    EXECUTE IMMEDIATE 'GRANT CREATE SESSION TO ' || u.name;
    EXECUTE IMMEDIATE 'GRANT SELECT ON hybrid.customers TO ' || u.name;
    EXECUTE IMMEDIATE 'GRANT EXECUTE ON hybrid.issue_token TO ' || u.name;
    EXECUTE IMMEDIATE 'GRANT SELECT ON hybrid.order_totals TO ' || u.name;
    EXECUTE IMMEDIATE 'CREATE SYNONYM ' || u.name || '.customers FOR hybrid.customers';
    EXECUTE IMMEDIATE 'CREATE SYNONYM ' || u.name || '.order_totals FOR hybrid.order_totals';
  END LOOP;
END;
/
COMMIT;

EXIT
