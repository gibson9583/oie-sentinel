// OIE Sentinel: the ${matches} template token is offered in the email subject
// and the webhook body, with a one-line note, and ${matchedLines} is gone.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { load, nodes } from './harness.mjs';

const mod = await load('web/pages/actions.jsx',
    'export { platform, EMAIL_SUBJECT_TOKENS, WEBHOOK_BODY_TOKENS, EmailConfigFields, WebhookConfigFields };');

/* The chips a config fieldset offers, the note under them, and the drop target. */
function chips(fieldset) {
    const tree = nodes(fieldset);
    const chipsNode = tree.find((n) => typeof n.type === 'function' && n.type.name === 'TokenChips');
    return {
        tokens: nodes(chipsNode.type(chipsNode.props)).filter((n) => n.type === 'button').map((n) => n.children.join('')),
        note: String(chipsNode.props.note || ''),
        target: tree.find((n) => n.props && n.props['data-sn-token-target'] === '1'),
    };
}

test('the email subject and the webhook body offer ${matches} and explain it', () => {
    for (const list of [mod.EMAIL_SUBJECT_TOKENS, mod.WEBHOOK_BODY_TOKENS]) {
        assert.ok(list.includes('matches'));
        assert.equal(list.includes('matchedLines'), false);
    }
    const email = chips(mod.EmailConfigFields({ config: {}, setConfig: () => {} }));
    const webhook = chips(mod.WebhookConfigFields({ config: { url: '', bodyTemplate: '', headers: [] }, setConfig: () => {} }));
    for (const [c, target] of [[email, 'input'], [webhook, 'textarea']]) {
        assert.ok(c.tokens.includes('${matches}'));
        assert.match(c.note, /\$\{matches\} holds the matched log lines of Log monitors that include them, and is empty otherwise\./);
        assert.equal(c.target.type, target, 'the template accepts dropped tokens');
    }
});
