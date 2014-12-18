CREATE TABLE INDEXES (
  DB_ID INT NOT NULL,
  TID INT NOT NULL,
  INDEX_NAME VARCHAR2(128) NOT NULL,
  COLUMN_NAME VARCHAR2(128) NOT NULL,
  DATA_TYPE VARCHAR2(128) NOT NULL,
  INDEX_TYPE CHAR(32) NOT NULL,
  IS_UNIQUE CHAR NOT NULL,
  IS_CLUSTERED CHAR NOT NULL,
  IS_ASCENDING CHAR NOT NULL,
  CONSTRAINT INDEXES_PKEY PRIMARY KEY (DB_ID, INDEX_NAME),
  FOREIGN KEY (DB_ID) REFERENCES DATABASES_ (DB_ID) ON DELETE CASCADE,
  FOREIGN KEY (TID) REFERENCES TABLES (TID) ON DELETE CASCADE
)