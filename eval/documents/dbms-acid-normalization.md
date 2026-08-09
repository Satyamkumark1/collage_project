# ACID Properties and Normalization in DBMS

ACID stands for Atomicity, Consistency, Isolation, and Durability. These four properties
guarantee that database transactions are processed reliably even in the presence of errors,
power failures, or concurrent access.

## Atomicity

Atomicity means a transaction is all-or-nothing: either every operation in it succeeds, or none
of them take effect. If a transaction transferring money between two bank accounts fails halfway
through, atomicity ensures neither account is debited or credited — the whole operation rolls
back to its state before the transaction began.

## Consistency

Consistency means a transaction takes the database from one valid state to another, preserving
all defined rules such as constraints, cascades, and triggers. A transaction that would violate a
constraint (for example, a foreign key pointing to a row that doesn't exist) is rejected entirely
rather than partially applied.

## Isolation

Isolation ensures that concurrently executing transactions do not interfere with each other's
intermediate, uncommitted state. Different isolation levels — read uncommitted, read committed,
repeatable read, and serializable — trade off consistency guarantees against concurrency
performance; serializable is the strictest and slowest, read uncommitted the loosest and fastest.

## Durability

Durability means that once a transaction commits, its changes survive any subsequent system
crash. This is typically implemented by writing changes to a write-ahead log (WAL) on durable
storage before acknowledging the commit back to the client.

## Normalization

Normalization is the process of organizing columns and tables in a relational database to
minimize data redundancy and avoid update, insertion, and deletion anomalies.

### First Normal Form (1NF)

A table is in 1NF if every column holds atomic (indivisible) values and there are no repeating
groups — for example, a single "phone numbers" column holding a comma-separated list of numbers
violates 1NF.

### Second Normal Form (2NF)

A table is in 2NF if it is in 1NF and every non-key column depends on the whole primary key, not
just part of it. 2NF violations only arise when the primary key is composite (made of more than
one column).

### Third Normal Form (3NF)

A table is in 3NF if it is in 2NF and has no transitive dependency — that is, no non-key column
depends on another non-key column rather than directly on the primary key. For example, storing
both `city` and `state` when `state` can be derived from `city` via a lookup is a transitive
dependency and violates 3NF.

### Functional Dependency

A functional dependency `A -> B` means that for any two rows with the same value of `A`, the
value of `B` must also be the same. Functional dependencies are the formal basis normalization
rules are built on.
