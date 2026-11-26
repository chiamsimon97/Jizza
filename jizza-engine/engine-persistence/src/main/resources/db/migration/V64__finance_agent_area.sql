-- Kai's area names goals, loans and insurance too, so a chat message about saving for something
-- or a loan is routed to Kai rather than answered by Jizza. Only the product's own wording is
-- replaced; a description the user changed stays as it is.
update agents
   set description = 'Money: goals and savings, loans, insurance, investments and brokers, accounts, spending, budgets, bills, tax.'
 where key = 'finance'
   and not is_orchestrator
   and description = 'Money: accounts, spending, budgets, bills, investments, tax.';
