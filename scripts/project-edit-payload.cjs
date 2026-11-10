'use strict';

// Test fixtures must send the same ordinary-edit projection as web/app.js editForm.
// GET /projects also returns ownership/workflow projections. Sending those back is
// intentionally rejected by LegacyOwnershipIntegration.guardMutation (HTTP 400).
// Keep source links here; individual negative tests may explicitly override them
// afterwards to exercise their original 409 source/status protection assertions.
const ordinaryProjectFields = Object.freeze([
  'title', 'unit', 'hours', 'amount', 'start_date', 'end_date', 'owner',
  'participant_count', 'delivery_mode', 'venue', 'contract_no', 'remark',
]);

function projectEditFields(row) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) {
    throw new TypeError('An existing synthetic project row is required');
  }
  return {
    ...Object.fromEntries(ordinaryProjectFields.map(key => [key, row[key]])),
    demand_id: row.demand_id, bid_id: row.bid_id, id: row.id,
  };
}

module.exports = { ordinaryProjectFields, projectEditFields };
