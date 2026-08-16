pm.test('status is ok', () => pm.expect(pm.response.code).to.eql(200));
const body = pm.response.json();
pm.environment.set('id', body.id);
pm.test('has id', () => pm.expect(body.id).to.eql(42));
