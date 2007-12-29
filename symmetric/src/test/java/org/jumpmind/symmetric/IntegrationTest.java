package org.jumpmind.symmetric;

import java.sql.Timestamp;
import java.sql.Types;
import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.time.DateUtils;
import org.jumpmind.symmetric.common.Constants;
import org.jumpmind.symmetric.common.TestConstants;
import org.jumpmind.symmetric.model.OutgoingBatch;
import org.jumpmind.symmetric.service.INodeService;
import org.jumpmind.symmetric.service.IOutgoingBatchService;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testng.Assert;
import org.testng.ITest;
import org.testng.annotations.Test;

public class IntegrationTest extends AbstractIntegrationTest implements ITest {

    private JdbcTemplate rootJdbcTemplate;

    private JdbcTemplate clientJdbcTemplate;

    static final String insertOrderHeaderSql = "insert into test_order_header (order_id, customer_id, status, deliver_date) values(?,?,?,?)";

    static final String updateOrderHeaderStatusSql = "update test_order_header set status = ? where order_id = ?";

    static final String selectOrderHeaderSql = "select order_id, customer_id, status, deliver_date from test_order_header where order_id = ?";

    static final String insertOrderDetailSql = "insert into test_order_detail (order_id, line_number, item_type, item_id, quantity, price) values(?,?,?,?,?,?)";

    static final String insertCustomerSql = "insert into test_customer (customer_id, name, is_active, address, city, state, zip, entry_time, notes, icon) values(?,?,?,?,?,?,?,?,?,?)";

    static final String insertTestTriggerTableSql = "insert into test_triggers_table (string_one_value, string_two_value) values(?,?)";

    static final String updateTestTriggerTableSql = "update test_triggers_table set string_one_value=?";

    static final byte[] BINARY_DATA = new byte[] { 0x01, 0x02, 0x03 };

    public String getTestName() {
        try {
            return "Test from " + getRootDatabaseName() + " to " + getClientDatabaseName();
        } catch (RuntimeException ex) {
            logger.error(ex, ex);
            throw ex;
        }
    }

    @Test(testName = "Integration Test", groups = "continuous", timeOut = 120000)
    public void testLifecycle() {
        try {
            init();
            register();
            initialLoad();
            testSyncToClient();
            testSyncToRootAutoGeneratedPrimaryKey();
            testSyncToRoot();
            testSyncInsertCondition();
            testSyncUpdateCondition();
            testIgnoreNodeChannel();
            testPurge();
            testHeartbeat();
        } catch (AssertionError ex) {
            throw ex;
        } catch (Exception ex) {
            logger.error(ex, ex);
            Assert.fail();
        }

    }

    protected void init() {
        BeanFactory rootBeanFactory = getRootEngine().getApplicationContext();
        rootJdbcTemplate = new JdbcTemplate((DataSource) rootBeanFactory.getBean(Constants.DATA_SOURCE));

        BeanFactory clientBeanFactory = getClientEngine().getApplicationContext();
        clientJdbcTemplate = new JdbcTemplate((DataSource) clientBeanFactory.getBean(Constants.DATA_SOURCE));
    }

    protected void register() {
        getRootEngine().openRegistration(TestConstants.TEST_CLIENT_NODE_GROUP, TestConstants.TEST_CLIENT_EXTERNAL_ID);
        getClientEngine().start();
        Assert.assertTrue(getClientEngine().isRegistered(), "The client did not register.");
    }

    protected void initialLoad() {
        INodeService nodeService = (INodeService) getRootEngine().getApplicationContext().getBean(
                Constants.NODE_SERVICE);
        getRootEngine().reloadNode(
                nodeService.findNodeByExternalId(TestConstants.TEST_CLIENT_NODE_GROUP,
                        TestConstants.TEST_CLIENT_EXTERNAL_ID).getNodeId());
        getClientEngine().pull();

        // TODO - need to add validation here
    }

    protected void testSyncToClient() {
        // test pulling no data
        getClientEngine().pull();

        // now change some data that should be sync'd
        rootJdbcTemplate.update(insertCustomerSql, new Object[] { 101, "Charlie Brown", "1", "300 Grub Street",
                "New Yorl", "NY", 90009, new Date(), "This is a test", BINARY_DATA });

        getClientEngine().pull();
        Assert.assertEquals(clientJdbcTemplate.queryForInt("select count(*) from test_customer where customer_id=101"),
                1, "The customer was not sync'd to the client.");

        if (getRootDbDialect().isClobSyncSupported()) {
            Assert.assertEquals(clientJdbcTemplate.queryForObject(
                    "select notes from test_customer where customer_id=101", String.class), "This is a test",
                    "The CLOB notes field on customer was not sync'd to the client.");
        }

        if (getRootDbDialect().isBlobSyncSupported()) {
            byte[] data = (byte[]) clientJdbcTemplate.queryForObject(
                    "select icon from test_customer where customer_id=101", byte[].class);
            Assert.assertTrue(ArrayUtils.isEquals(data, BINARY_DATA),
                    "The BLOB icon field on customer was not sync'd to the client.");
        }

    }

    protected void testSyncToRootAutoGeneratedPrimaryKey() {
        final String NEW_VALUE = "unique new value one value";
        clientJdbcTemplate.update(insertTestTriggerTableSql, new Object[] { "value one", "value \" two" });
        getClientEngine().push();
        clientJdbcTemplate.update(updateTestTriggerTableSql, new Object[] { NEW_VALUE });
        getClientEngine().push();
        Assert.assertEquals(rootJdbcTemplate.queryForInt(
                "select count(*) from test_triggers_table where string_one_value=?", new Object[] { NEW_VALUE }), 1,
                "The update on test_triggers_table did not work.");
    }

    protected void testSyncToRoot() throws ParseException {
        Date date = DateUtils.parseDate("2007-01-03", new String[] { "yyyy-MM-dd" });
        clientJdbcTemplate.update(insertOrderHeaderSql, new Object[] { "10", 100, null, date }, new int[] {
                Types.VARCHAR, Types.INTEGER, Types.CHAR, Types.DATE });
        clientJdbcTemplate.update(insertOrderDetailSql, new Object[] { "10", 1, "STK", "110000065", 3, 3.33 });
        getClientEngine().push();
    }

    protected void testSyncInsertCondition() throws ParseException {
        // Should not sync when status = null
        Date date = DateUtils.parseDate("2007-01-02", new String[] { "yyyy-MM-dd" });
        rootJdbcTemplate.update(insertOrderHeaderSql, new Object[] { "11", 100, null, date }, new int[] {
                Types.VARCHAR, Types.INTEGER, Types.CHAR, Types.DATE });
        getClientEngine().pull();

        IOutgoingBatchService outgoingBatchService = (IOutgoingBatchService) getRootEngine().getApplicationContext()
                .getBean(Constants.OUTGOING_BATCH_SERVICE);
        List<OutgoingBatch> batches = outgoingBatchService.getOutgoingBatches(TestConstants.TEST_CLIENT_EXTERNAL_ID);
        Assert.assertEquals(batches.size(), 0, "There should be no outgoing batches, yet I found some.");

        Assert.assertEquals(clientJdbcTemplate.queryForList(selectOrderHeaderSql, new Object[] { "11" }).size(), 0,
                "The order record was sync'd when it should not have been.");

        // Should sync when status = C
        rootJdbcTemplate.update(insertOrderHeaderSql, new Object[] { "12", 100, "C", date }, new int[] { Types.VARCHAR,
                Types.INTEGER, Types.CHAR, Types.DATE });
        getClientEngine().pull();
        Assert.assertEquals(clientJdbcTemplate.queryForList(selectOrderHeaderSql, new Object[] { "12" }).size(), 1,
                "The order record was not sync'd when it should have been.");
        // TODO: make sure event did not fire
    }

    @SuppressWarnings("unchecked")
    protected void testSyncUpdateCondition() {
        rootJdbcTemplate.update(updateOrderHeaderStatusSql, new Object[] { null, "1" });
        getClientEngine().pull();
        Assert.assertEquals(clientJdbcTemplate.queryForList(selectOrderHeaderSql, new Object[] { "1" }).size(), 0,
                "The order record was sync'd when it should not have been.");

        rootJdbcTemplate.update(updateOrderHeaderStatusSql, new Object[] { "C", "1" });
        getClientEngine().pull();
        List list = clientJdbcTemplate.queryForList(selectOrderHeaderSql, new Object[] { "1" });
        Assert.assertEquals(list.size(), 1, "The order record should exist.");
        Map map = (Map) list.get(0);
        Assert.assertEquals(map.get("status"), "C", "Status should be complete");
        // TODO: make sure event did not fire
    }

    @SuppressWarnings("unchecked")
    protected void testIgnoreNodeChannel() {
        INodeService nodeService = (INodeService) getRootEngine().getApplicationContext().getBean("nodeService");
        nodeService.ignoreNodeChannelForExternalId(true, TestConstants.TEST_CHANNEL_ID,
                TestConstants.TEST_ROOT_NODE_GROUP, TestConstants.TEST_ROOT_EXTERNAL_ID);
        rootJdbcTemplate.update(insertCustomerSql, new Object[] { 201, "Charlie Dude", "1", "300 Grub Street",
                "New Yorl", "NY", 90009, new Date(), "This is a test", BINARY_DATA });
        getClientEngine().pull();
        Assert.assertEquals(clientJdbcTemplate.queryForInt("select count(*) from test_customer where customer_id=201"),
                0, "The customer was sync'd to the client.");
        nodeService.ignoreNodeChannelForExternalId(false, TestConstants.TEST_CHANNEL_ID,
                TestConstants.TEST_ROOT_NODE_GROUP, TestConstants.TEST_ROOT_EXTERNAL_ID);

    }

    protected void testPurge() throws Exception {
        Thread.sleep(1000);
        getRootEngine().purge();
        getClientEngine().purge();
        Assert.assertEquals(rootJdbcTemplate.queryForInt("select count(*) from " + TestConstants.TEST_PREFIX + "data"),
                0, "Expected all data rows to have been purged.");
        Assert.assertEquals(clientJdbcTemplate
                .queryForInt("select count(*) from " + TestConstants.TEST_PREFIX + "data"), 0,
                "Expected all data rows to have been purged.");

    }

    protected void testHeartbeat() throws Exception {
        long ts = System.currentTimeMillis();
        Thread.sleep(1000);
        getClientEngine().heartbeat();
        getClientEngine().push();
        Date time = (Date) rootJdbcTemplate.queryForObject("select heartbeat_time from " + TestConstants.TEST_PREFIX
                + "node where external_id='" + TestConstants.TEST_CLIENT_EXTERNAL_ID + "'", Timestamp.class);
        Assert.assertTrue(time != null && time.getTime() > ts,
                "The client node was not sync'd to the root as expected.");
    }

    protected void testMultipleChannels() {
    }

    protected void testChannelInError() {
    }

    protected void testTableSyncConfigChangeForRoot() {
    }

    protected void testTableSyncConfigChangeForClient() {
    }

    protected void testDataChangeTableChangeDataChangeThenSync() {
    }

    protected void testTransactionalCommit() {
    }

    protected void testTransactionalCommitPastBatchBoundary() {
    }

    protected void testSyncingGlobalParametersFromRoot() {
    }

    protected void testRejectedRegistration() {
    }

}
