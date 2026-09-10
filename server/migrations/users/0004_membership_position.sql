-- Per-member list ordering (F5.5, PROTOCOL.md "Ordering the lists").
--
-- On memberships rather than on the list itself: a list is shared, and the two
-- people on it each drag their own copy around. Putting the key on the list
-- would make one person's reorder land on the other's screen, and putting it in
-- the ListRoom changelog would broadcast it to everyone on the list.
--
-- Null for every membership that existed before this migration, and for every
-- one made since that has not been dragged. Null sorts last, by age, so a list
-- somebody was just invited to appears at the bottom rather than in the middle.
ALTER TABLE memberships ADD COLUMN position TEXT;

INSERT INTO _migrations (version) VALUES (4);
