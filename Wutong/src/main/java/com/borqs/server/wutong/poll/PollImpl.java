package com.borqs.server.wutong.poll;

import com.borqs.server.base.conf.Configuration;
import com.borqs.server.base.conf.GlobalConfig;
import com.borqs.server.base.context.Context;
import com.borqs.server.base.data.Record;
import com.borqs.server.base.data.RecordSet;
import com.borqs.server.base.data.Schema;
import com.borqs.server.base.data.Schemas;
import com.borqs.server.base.log.Logger;
import com.borqs.server.base.sql.ConnectionFactory;
import com.borqs.server.base.sql.SQLExecutor;
import com.borqs.server.base.sql.SQLTemplate;
import com.borqs.server.base.sql.SQLUtils;
import com.borqs.server.base.util.DateUtils;
import com.borqs.server.base.util.Initializable;
import com.borqs.server.base.util.StringUtils2;
import com.borqs.server.base.util.json.JsonUtils;
import com.borqs.server.wutong.Constants;
import com.borqs.server.wutong.GlobalLogics;
import com.borqs.server.wutong.account2.AccountLogic;
import com.borqs.server.wutong.commons.Commons;
import com.borqs.server.wutong.conversation.ConversationLogic;
import com.borqs.server.wutong.group.GroupLogic;
import com.borqs.server.wutong.page.PageLogicUtils;
import com.borqs.server.wutong.stream.StreamLogic;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.codehaus.jackson.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class PollImpl implements PollLogic, Initializable {
    private static final Logger L = Logger.getLogger(PollImpl.class);
    public final Schema pollSchema = Schema.loadClassPath(PollImpl.class, "poll.schema");
    public final Schema itemSchema = Schema.loadClassPath(PollImpl.class, "item.schema");
    private ConnectionFactory connectionFactory;
    private String db;


    public void init() {
        Configuration conf = GlobalConfig.get();
        pollSchema.loadAliases(conf.getString("schema.poll.alias", null));
        this.connectionFactory = ConnectionFactory.getConnectionFactory(conf.getString("poll.simple.connectionFactory", "dbcp"));
        this.db = conf.getString("poll.simple.db", null);
    }

    @Override
    public void destroy() {

    }

    private SQLExecutor getSqlExecutor() {
        return new SQLExecutor(connectionFactory, db);
    }

    @Override
    public long createPoll(Context ctx, Record poll, RecordSet items) {
        final String METHOD = "createPoll";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, poll, items);
        Schemas.standardize(pollSchema, poll);

        ArrayList<String> sqls = new ArrayList<String>();
        final String SQL = "INSERT INTO ${table} ${values_join(alias, rec)}";
        String sql = SQLTemplate.merge(SQL,
                "table", "poll",
                "alias", pollSchema.getAllAliases(),
                "rec", poll);
        sqls.add(sql);

        for (Record item : items) {
            sql = SQLTemplate.merge(SQL,
                    "table", "poll_items",
                    "alias", itemSchema.getAllAliases(),
                    "rec", item);
            sqls.add(sql);
        }

        SQLExecutor se = getSqlExecutor();
        L.op(ctx, "createPoll");
        long n = se.executeUpdate(sqls);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return poll.getInt("id");
    }



    @Override
    public boolean vote(Context ctx, String userId, long pollId, Record items) {
        final String METHOD = "vote";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, userId, pollId, items);
        String sql = "SELECT mode FROM poll WHERE id=" + pollId;
        SQLExecutor se = getSqlExecutor();
        long mode = se.executeIntScalar(sql, 0);
        if (mode == 2) {
            sql = "DELETE FROM poll_participants WHERE poll_id=" + pollId + " AND user=" + userId;
            se.executeUpdate(sql);
        }

        sql = "INSERT INTO poll_participants (poll_id, item_id, user, weight, created_time) VALUES ";
        for (Map.Entry<String, Object> entry : items.entrySet()) {
            String item = entry.getKey();
            long weight = ((Long) entry.getValue()).longValue();
            long now = DateUtils.nowMillis();

            sql += "(" + pollId + ", " + item + ", " + userId + ", " + weight + ", " + now + "), ";
        }

        sql = StringUtils.substringBeforeLast(sql, ",");
        if (mode == 1) {
            sql += " ON DUPLICATE KEY UPDATE poll_id=VALUES(poll_id), weight=VALUES(weight), created_time=VALUES(created_time)";
        }
        L.op(ctx, "vote");
        long n = se.executeUpdate(sql);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return n > 0;
    }

    @Override
    public RecordSet getPolls(Context ctx, String pollIds) {
        final String METHOD = "getPolls";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, pollIds);
        String sql = "SELECT * FROM poll WHERE id IN (" + pollIds + ") order by created_time desc";
        SQLExecutor se = getSqlExecutor();
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return se.executeRecordSet(sql, null);
    }

    @Override
    public RecordSet getItemsByPollId(Context ctx, long pollId) {
        final String METHOD = "getItemsByPollId";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, pollId);
        String sql = "SELECT * FROM poll_items WHERE poll_id=" + pollId + " order by index_";
        SQLExecutor se = getSqlExecutor();
        RecordSet recs = se.executeRecordSet(sql, null);

        sql = "SELECT item_id, sum(weight) AS count FROM poll_participants WHERE poll_id=" + pollId + " group by item_id";
        RecordSet counts = se.executeRecordSet(sql, null);
        Record r = new Record();
        for (Record count : counts)
            r.put(count.getString("item_id"), count.getInt("count", 0));

        for (Record rec : recs) {
            String itemId = rec.getString("item_id");
            rec.put("count", r.getInt(itemId));

            sql = "SELECT user, created_time FROM poll_participants WHERE poll_id=" + pollId + " AND item_id=" + itemId;
            RecordSet participants = se.executeRecordSet(sql, null);
            participants.renameColumn("created_time", "voted_time");
            rec.put("participants", participants);
        }

        recs.removeColumns("poll_id");
        recs.renameColumn("item_id", "id");
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return recs;
    }

    @Override
    public long hasVoted(Context ctx, String userId, long pollId) {
        final String METHOD = "hasVoted";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, userId, pollId);
        String sql = "SELECT sum(weight) FROM poll_participants WHERE poll_id=" + pollId + " AND user=" + userId;
        SQLExecutor se = getSqlExecutor();
        long n = se.executeIntScalar(sql, 0);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return n;
    }

    @Override
    public RecordSet getItemsByItemIds(Context ctx, String itemIds) {
        final String METHOD = "getCounts";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, itemIds);

        if (StringUtils.isBlank(itemIds))
            return new RecordSet();

        String sql = "SELECT * FROM poll_items WHERE item_id IN (" + itemIds + ") order by index_";
        SQLExecutor se = getSqlExecutor();
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return se.executeRecordSet(sql, null);
    }

    @Override
    public Record getCounts(Context ctx, String pollIds) {
        final String METHOD = "getCounts";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, pollIds);
        String sql = "SELECT poll_id, count(distinct user) AS count FROM poll_participants WHERE poll_id IN (" + pollIds + ") group by poll_id";
        SQLExecutor se = getSqlExecutor();
        RecordSet counts = se.executeRecordSet(sql, null);
        Record r = new Record();
        for (Record count : counts)
            r.put(count.getString("poll_id"), count.getInt("count", 0));
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return r;
    }

    private String pollListFilter(Context ctx, String sql, String viewerId, String userId, int page, int count) {
        final String METHOD = "pollListFilter";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, userId, page, count);

        sql += " AND (privacy=0";
        if (isFriend(ctx, userId, viewerId))
            sql += " OR privacy=1";
        sql += " OR (privacy=2 AND " + isTarget(ctx, viewerId) + ")";
        sql += ") order by created_time desc";
        sql += SQLTemplate.merge(" ${limit}",
                "limit", SQLUtils.pageToLimit(page, count));
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return sql;
    }

    @Override
    public String getCreatedPolls(Context ctx, String viewerId, String userId, int page, int count) {
        final String METHOD = "getCreatedPolls";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, userId, page, count);
        String sql = "SELECT id FROM poll WHERE source=" + userId;
        sql = pollListFilter(ctx, sql, viewerId, userId, page, count);

        SQLExecutor se = getSqlExecutor();
        RecordSet recs = se.executeRecordSet(sql, null);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return recs.joinColumnValues("id", ",");
    }

    @Override
    public String getParticipatedPolls(Context ctx, String viewerId, String userId, int page, int count) {
        final String METHOD = "getParticipatedPolls";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, userId, page, count);
        String sql = "SELECT distinct poll_id AS poll_id FROM poll_participants WHERE user=" + userId + " order by created_time desc";

        SQLExecutor se = getSqlExecutor();
        RecordSet recs = se.executeRecordSet(sql, null);
        String pollIds = recs.joinColumnValues("poll_id", ",");
        sql = "SELECT id FROM poll WHERE id IN (" + pollIds + ")";
        sql = pollListFilter(ctx, sql, viewerId, userId, page, count);

        recs = se.executeRecordSet(sql, null);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return recs.joinColumnValues("id", ",");
    }

    private String inTargetGroup(Context ctx, String userId) {
        String sql0 = "SELECT group_id FROM group_members WHERE member=" + userId + " AND destroyed_time=0";
        String sql = "SELECT id FROM group_ WHERE id IN (" + sql0 + ") AND destroyed_time=0";

        SQLExecutor se = getSqlExecutor();
        RecordSet recs = se.executeRecordSet(sql, null);
        ArrayList<String> groupIds = new ArrayList<String>();
        for (Record rec : recs) {
            groupIds.add(rec.getString("id"));
        }

        String condition = "";

        for (String groupId : groupIds) {
            condition += " or instr(concat(',',target,','),concat(','," + groupId + ",','))>0";
        }

        return condition;
    }

    private boolean isFriend(Context ctx, String source, String viewerId) {
        if (StringUtils.equals(source, viewerId))
            return true;

        String sql = "SELECT count(*) AS count FROM friend WHERE user=" + source + " AND friend=" + viewerId + " and circle<>4 and reason<>5 and reason<>6";
        SQLExecutor se = getSqlExecutor();
        long count = se.executeIntScalar(sql, 0);
        return count > 0;
    }

    private String isTarget(Context ctx, String viewerId) {
        String sql0 = "SELECT group_id FROM group_members WHERE member=" + viewerId + " AND destroyed_time=0";
        String sql = "SELECT id FROM group_ WHERE id IN (" + sql0 + ") AND destroyed_time=0";

        SQLExecutor se = getSqlExecutor();
        RecordSet recs = se.executeRecordSet(sql, null);
        ArrayList<String> groupIds = new ArrayList<String>();
        for (Record rec : recs) {
            groupIds.add("," + rec.getString("id") + ",");
        }
        groupIds.add("," + viewerId + ",");
        String arg = "'" + StringUtils2.joinIgnoreBlank("|", groupIds) + "'";
        return "concat(',',target,',') regexp concat(" + arg + ")";
    }

    @Override
    public String getInvolvedPolls(Context ctx, String viewerId, String userId, int page, int count) {
        final String METHOD = "getInvolvedPolls";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, userId, page, count);

        String sql = "SELECT id FROM poll WHERE instr(concat(',',target,','),concat(','," + userId + ",','))>0" + inTargetGroup(ctx, userId);

        long id = 0;

        id = Long.parseLong(userId);
        if (id >= Constants.PUBLIC_CIRCLE_ID_BEGIN && id <= Constants.GROUP_ID_END) {
            sql = "SELECT id FROM poll WHERE instr(concat(',',target,','),concat(','," + userId + ",','))>0";
        }


        sql = pollListFilter(ctx, sql, viewerId, userId, page, count);

        SQLExecutor se = getSqlExecutor();
        RecordSet recs = se.executeRecordSet(sql, null);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return recs.joinColumnValues("id", ",");
    }

    @Override
    public String getFriendsPolls(Context ctx, String viewerId, String userId, int sort, int page, int count) {
        final String METHOD = "getFriendsPolls";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, userId, page, count);

        String sql = "select distinct(friend) AS friend from friend where user=" + userId + " and circle<>4 and reason<>5 and reason<>6";
        SQLExecutor se = getSqlExecutor();
        RecordSet friends = se.executeRecordSet(sql, null);
        String friendIds = friends.joinColumnValues("friend", ",");

        if (StringUtils.isBlank(friendIds)) {
            return "";
        }
        else {
            sql = "SELECT id FROM poll WHERE source IN (" + friendIds + ")";
            sql = pollListFilter(ctx, sql, viewerId, userId, page, count);

            RecordSet recs = se.executeRecordSet(sql, null);
            if (L.isTraceEnabled())
                L.traceEndCall(ctx, METHOD);
            return recs.joinColumnValues("id", ",");
        }
    }

    @Override
    public String getPublicPolls(Context ctx, String viewerId, String userId, int sort, int page, int count) {
        final String METHOD = "getPublicPolls";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, userId, page, count);
        String sql = "SELECT id FROM poll WHERE 1=1";
        sql = pollListFilter(ctx, sql, viewerId, userId, page, count);

        SQLExecutor se = getSqlExecutor();
        RecordSet recs = se.executeRecordSet(sql, null);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return recs.joinColumnValues("id", ",");
    }

    @Override
    public long getRelatedPollCount(Context ctx, String viewerId, String userId) {
        final String METHOD = "getRelatedPollCount";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, userId);
        PollLogic pollLogic = GlobalLogics.getPoll();
        long id = 0;
        try {
            id = Long.parseLong(userId);
            if (id >= Constants.PUBLIC_CIRCLE_ID_BEGIN && id <= Constants.GROUP_ID_END) {
                String involvedIds = (pollLogic.getInvolvedPolls(ctx, viewerId, userId, -1, -1));
                Set<String> involved = StringUtils2.splitSet(involvedIds, ",", true);
                return involved.size();
            }
        } catch (NumberFormatException nfe) {

        }
        String createdIds = (pollLogic.getCreatedPolls(ctx, viewerId, userId, -1, -1));
        Set<String> created = StringUtils2.splitSet(createdIds, ",", true);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return created.size();
    }

    @Override
    public long createPoll(Context ctx, Record poll, RecordSet items, String ua, String loc, String appId, boolean sendPost) {
        final String METHOD = "getByUsers";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, poll, items, ua, loc, appId, sendPost);
        PollLogic pollLogic = GlobalLogics.getPoll();
        ConversationLogic conversationLogic = GlobalLogics.getConversation();
        StreamLogic streamLogic = GlobalLogics.getStream();
        L.op(ctx, "createPoll");
        long pollId = pollLogic.createPoll(ctx, poll, items);
        String viewerId = poll.getString("source");
        String mentions = poll.getString("target");
        String title = poll.getString("title");
        conversationLogic.createConversationP(ctx, Constants.POLL_OBJECT, String.valueOf(pollId), Constants.C_POLL_CREATE, viewerId);

        //send a post
        if (sendPost) {
            String m = "";
            String tempNowAttachments = "[]";
            String template = Constants.getBundleString(ua, "platform.create.poll.message");
            String pollSchema = "<a href=\"borqs://poll/details?id=" + pollId + "\">" + title + "</a>";
            String message = SQLTemplate.merge(template, new Object[][]{
                    {"title", pollSchema}
            });

            long privacy = poll.getInt("privacy");
            boolean secretly = (privacy == 2);

            streamLogic.autoPost(ctx, viewerId, Constants.VOTE_POST, message, tempNowAttachments, appId, "", "", m, mentions, secretly, "", ua, loc, true, true, true, "", "", false);
        }

        if (StringUtils.isNotBlank(mentions)) {
            AccountLogic accountLogic = GlobalLogics.getAccount();
            Record source = accountLogic.getUser(ctx, viewerId, viewerId, "user_id, display_name, remark, image_url,perhaps_name");
            String sourceName = source.getString("display_name");

            Commons.sendNotification(ctx, Constants.NTF_POLL_INVITE,
                    Commons.createArrayNodeFromStrings(appId),
                    Commons.createArrayNodeFromStrings(viewerId),
                    Commons.createArrayNodeFromStrings(title, sourceName),
                    Commons.createArrayNodeFromStrings(),
                    Commons.createArrayNodeFromStrings(),
                    Commons.createArrayNodeFromStrings(String.valueOf(pollId)),
                    Commons.createArrayNodeFromStrings(title, String.valueOf(pollId), viewerId, sourceName),
                    Commons.createArrayNodeFromStrings(),
                    Commons.createArrayNodeFromStrings(),
                    Commons.createArrayNodeFromStrings(),
                    Commons.createArrayNodeFromStrings(mentions)
            );
        }
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return pollId;

    }

    @Override
    public RecordSet getPolls(Context ctx, String viewerId, String pollIds, boolean withItems) {
        final String METHOD = "getPolls";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, pollIds, withItems);
        PollLogic pollLogic = GlobalLogics.getPoll();
        GroupLogic groupLogic = GlobalLogics.getGroup();

        if (StringUtils.isBlank(pollIds))
            return new RecordSet();

        RecordSet polls = pollLogic.getPolls(ctx, pollIds);
        Record counts = pollLogic.getCounts(ctx, pollIds);
        AccountLogic account = GlobalLogics.getAccount();
        for (Record poll : polls) {
            long pollId = poll.getInt("id");
            poll.put("id", ObjectUtils.toString(pollId));

            String sourceId = poll.getString("source");
            Record source = account.getUser(ctx, sourceId, sourceId, "user_id, display_name, remark, image_url,perhaps_name");
            poll.put("source", source);

            long startTime = poll.getInt("start_time");
            long endTime = poll.getInt("end_time");
            long now = DateUtils.nowMillis();
            int status = 0; //  0 - not start    1 - process  2 - end
            if (now < startTime)
                status = 0;
            else if (((now >= startTime) && (now <= endTime)) || endTime == 0)
                status = 1;
            else
                status = 2;
            poll.put("status", status);
            poll.put("left", endTime - now);

            poll.put("count", counts.getInt(String.valueOf(pollId)));

            String target = poll.getString("target");
            List<String> targetIds = StringUtils2.splitList(target, ",", true);

            List<String> groupIds = groupLogic.getGroupIdsFromMentions(ctx, targetIds);
            targetIds.removeAll(groupIds);

            PageLogicUtils.removeAllPageIds(ctx, targetIds);

            RecordSet targetUsers = account.getUsers(ctx, StringUtils2.joinIgnoreBlank(",", targetIds), "user_id, display_name, remark, image_url,perhaps_name");

            RecordSet targetGroups = groupIds.size() == 0 ? new RecordSet() : groupLogic.getGroups(ctx, Constants.PUBLIC_CIRCLE_ID_BEGIN, Constants.GROUP_ID_END, viewerId, StringUtils2.joinIgnoreBlank(",", groupIds), Constants.GROUP_LIGHT_COLS, false);
            targetUsers.addAll(targetGroups);
            poll.put("target", targetUsers);
            int commentCount = GlobalLogics.getComment().getCommentCountP(ctx, viewerId, Constants.POLL_OBJECT, String.valueOf(pollId));
            Record comments = new Record();
            comments.put("count", commentCount);
            poll.put("comments", comments);

            if (withItems) {
                long anonymous = poll.getInt("anonymous");
                RecordSet items = pollLogic.getItemsByPollId(ctx, pollId);
                for (Record item : items) {
                    long itemId = item.getInt("id");
                    item.put("id", ObjectUtils.toString(itemId));
                    RecordSet participants = RecordSet.fromJson(JsonUtils.toJson(item.get("participants"), false));
                    if (anonymous == 1)
                        item.put("participants", JsonNodeFactory.instance.arrayNode());
                    else {
                        boolean viewerVoted = false;
                        RecordSet participantsInFriends = new RecordSet();
                        for (Record participant : participants) {
                            String userId = participant.getString("user");
                            if (isFriend(ctx, viewerId, userId) || StringUtils.equals(viewerId, userId)) {
                                Record user = account.getUser(ctx, userId, userId, "user_id, display_name, remark, image_url,perhaps_name");
                                Record r = participant.copy();
                                r.put("user", user);
                                participantsInFriends.add(r);

                                if (StringUtils.equals(viewerId, userId) && !viewerVoted)
                                    viewerVoted = true;
                            }
                        }
                        item.put("participants", participantsInFriends);
                        item.put("viewer_voted", viewerVoted);
                    }
                }
                poll.put("items", items);
            }

            boolean canVote = canVote(ctx, viewerId, pollId);
            poll.put("viewer_can_vote", canVote);

            long mode = poll.getInt("mode");
            long multi = poll.getInt("multi");
            long hasVoted = pollLogic.hasVoted(ctx, viewerId, pollId);
            poll.put("has_voted", hasVoted > 0);

            long viewerLeft = 0;
            if (canVote) {
                if (mode == 0)
                    viewerLeft = multi;
                else if (mode == 1)
                    viewerLeft = multi - hasVoted;
                else
                    viewerLeft = multi;
            }
            poll.put("viewer_left", viewerLeft);
        }
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return polls;

    }


    @Override
    public boolean vote(Context ctx, String userId, long pollId, Record items, String ua, String loc, String appId, boolean sendPost) {
        final String METHOD = "vote";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, userId, pollId, items, ua, loc, appId, sendPost);
        PollLogic pollLogic = GlobalLogics.getPoll();
        AccountLogic accountLogic = GlobalLogics.getAccount();
        StreamLogic streamLogic = GlobalLogics.getStream();
        L.op(ctx, "vote");
        boolean b = pollLogic.vote(ctx, userId, pollId, items);

        if (b && sendPost) {
            Record poll = pollLogic.getPolls(ctx, String.valueOf(pollId)).getFirstRecord();
            String sourceId = poll.getString("source");
            Record source = accountLogic.getUser(ctx, sourceId, sourceId, "user_id, display_name, remark, image_url,perhaps_name");
            String sourceName = source.getString("display_name");
            String sourceSchema = "<a href=\"borqs://profile/details?uid=" + sourceId + "&tab=2\">" + sourceName + "</a>";

            String title = poll.getString("title");
            String pollSchema = "<a href=\"borqs://poll/details?id=" + pollId + "\">" + title + "</a>";

            String itemIds = StringUtils2.joinIgnoreBlank(",", items.keySet());
            RecordSet itemRecs = pollLogic.getItemsByItemIds(ctx, itemIds);
            String itemMsg = "【" + itemRecs.joinColumnValues("message", "】, 【") + "】";

            String m = "";
            String tempNowAttachments = "[]";
            String template = Constants.getBundleString(ua, "platform.vote.message");
            String message = SQLTemplate.merge(template, new Object[][]{
                    {"source", sourceSchema},
                    {"title", pollSchema},
                    {"items", itemMsg}
            });

            long privacy = poll.getInt("privacy");
            boolean secretly = (privacy == 2);
            String mentions = (privacy == 2) ? sourceId : "";

            streamLogic.autoPost(ctx, userId, Constants.VOTE_POST, message, tempNowAttachments, appId, "", "", m, mentions, secretly, "", ua, loc, true, true, true, "", "", false);
        }
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return b;

    }

    @Override
    public RecordSet getCreatedPollsPlatform(Context ctx, String viewerId, String userId, int page, int count) {
        final String METHOD = "getByUsers";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, userId, page, count);
        PollLogic pollLogic = GlobalLogics.getPoll();
        String pollIds = pollLogic.getCreatedPolls(ctx, viewerId, userId, page, count);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return pollLogic.getPolls(ctx, viewerId, pollIds, false);

    }

    @Override
    public RecordSet getParticipatedPollsPlatform(Context ctx, String viewerId, String userId, int page, int count) {
        final String METHOD = "getByUsers";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, userId, page, count);
        PollLogic pollLogic = GlobalLogics.getPoll();
        String pollIds = pollLogic.getParticipatedPolls(ctx, viewerId, userId, page, count);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return pollLogic.getPolls(ctx, viewerId, pollIds, false);

    }

    @Override
    public RecordSet getInvolvedPollsPlatform(Context ctx, String viewerId, String userId, int page, int count) {
        final String METHOD = "getByUsers";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, userId, page, count);
        PollLogic pollLogic = GlobalLogics.getPoll();
        String pollIds = pollLogic.getInvolvedPolls(ctx, viewerId, userId, page, count);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return pollLogic.getPolls(ctx, viewerId, pollIds, false);

    }

    @Override
    public RecordSet getFriendsPollsPlatform(Context ctx, String viewerId, String userId, int sort, int page, int count) {
        final String METHOD = "getByUsers";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, userId, page, count);
        PollLogic pollLogic = GlobalLogics.getPoll();
        String pollIds = pollLogic.getFriendsPolls(ctx, viewerId, userId, sort, page, count);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return pollLogic.getPolls(ctx, viewerId, pollIds, false);

    }

    @Override
    public RecordSet getPublicPollsPlatform(Context ctx, String viewerId, String userId, int sort, int page, int count) {
        final String METHOD = "getByUsers";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, userId, page, count);
        PollLogic pollLogic = GlobalLogics.getPoll();
        String pollIds = pollLogic.getPublicPolls(ctx, viewerId, userId, sort, page, count);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return pollLogic.getPolls(ctx, viewerId, pollIds, false);

    }

    @Override
    public boolean deletePolls(Context ctx, String viewerId, String pollIds) {
        final String METHOD = "getByUsers";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, pollIds);
        String sql = "UPDATE poll SET destroyed_time=" + DateUtils.nowMillis() + " WHERE id IN (" + pollIds + ") AND source=" + viewerId;
        SQLExecutor se = getSqlExecutor();
        L.op(ctx, "deletePolls");
        long n = se.executeUpdate(sql);
        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return n > 0;
    }

    @Override
    public boolean canVote(Context ctx, String viewerId, long pollId) {
        final String METHOD = "getByUsers";
        if (L.isTraceEnabled())
            L.traceStartCall(ctx, METHOD, viewerId, pollId);
        PollLogic pollLogic = GlobalLogics.getPoll();
        GroupLogic groupLogic = GlobalLogics.getGroup();
        AccountLogic accountLogic = GlobalLogics.getAccount();

        Record poll = pollLogic.getPolls(ctx, String.valueOf(pollId)).getFirstRecord();

        //poll mode
        long mode = poll.getInt("mode");
        long multi = poll.getInt("multi");
        long hasVoted = pollLogic.hasVoted(ctx, viewerId, pollId);
        boolean con1 = false;
        if (mode == 0)
            con1 = (hasVoted <= 0);
        else if (mode == 1)
            con1 = (hasVoted < multi);
        else
            con1 = true;

        //end time
        long startTime = poll.getInt("start_time");
        long endTime = poll.getInt("end_time");
        long now = DateUtils.nowMillis();
        int status = 0; //  0 - not start    1 - process  2 - end
        if (now < startTime)
            status = 0;
        else if (((now >= startTime) && (now <= endTime)) || endTime == 0)
            status = 1;
        else
            status = 2;
        boolean con2 = (status == 1);

        //privacy
        boolean con3 = false;
        long privacy = poll.getInt("privacy");
        String source = poll.getString("source");
        String targets = poll.getString("target");
        if (privacy == 0)
            con3 = true;
        else if (privacy == 1) {
            con3 = isFriend(ctx, source, viewerId);
        } else {
            List<String> l = StringUtils2.splitList(targets, ",", true);
            for (String target : l) {
                long id = 0;

                id = Long.parseLong(target);
                if (id >= Constants.PUBLIC_CIRCLE_ID_BEGIN && id <= Constants.GROUP_ID_END) {
                    if (groupLogic.hasRight(ctx, id, Long.parseLong(viewerId), Constants.ROLE_MEMBER)) {
                        con3 = true;
                        break;
                    }
                } else {
                    if (StringUtils.equals(viewerId, target)) {
                        con3 = true;
                        break;
                    }
                }

            }
        }

        //gender limit
        boolean con4 = false;
        long limit = poll.getInt("limit_");
        if (limit == 0)
            con4 = true;
        else {
            String gender = accountLogic.getUser(ctx, viewerId, viewerId, "gender").getString("gender");
            if (limit == 1)
                con4 = StringUtils.equals(gender, "m");
            else if (limit == 2)
                con4 = StringUtils.equals(gender, "f");
        }

        if (L.isTraceEnabled())
            L.traceEndCall(ctx, METHOD);
        return con1 && con2 && con3 && con4;

    }
}
