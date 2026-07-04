-- object: wireless_channels
-- folder: tables
-- depends_on: -
-- source: sql/oracle.sql lines 428-433

CREATE TABLE WIRELESS_CHANNELS (
    CHANNEL       NUMBER(3,0),
    FREQUENCY_MHZ NUMBER(6,0) NOT NULL,
    BAND          VARCHAR2(8) NOT NULL,
    CONSTRAINT WIRELESS_CHANNELS_PK PRIMARY KEY (CHANNEL, BAND)
);
