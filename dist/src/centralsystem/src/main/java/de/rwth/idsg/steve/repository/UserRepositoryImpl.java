package de.rwth.idsg.steve.repository;

import de.rwth.idsg.steve.SteveException;
import de.rwth.idsg.steve.repository.dto.User;
import de.rwth.idsg.steve.utils.CustomDSL;
import de.rwth.idsg.steve.web.dto.UserForm;
import de.rwth.idsg.steve.web.dto.UserQueryForm;
import jooq.steve.db.tables.records.UserRecord;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.jooq.Configuration;
import org.jooq.Record6;
import org.jooq.RecordMapper;
import org.jooq.Result;
import org.jooq.SelectQuery;
import org.jooq.TableField;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.util.List;

import static de.rwth.idsg.steve.utils.DateTimeUtils.humanize;
import static de.rwth.idsg.steve.utils.DateTimeUtils.toDateTime;
import static jooq.steve.db.tables.User.USER;

/**
 * @author Sevket Goekay <goekay@dbis.rwth-aachen.de>
 * @since 14.08.2014
 */
@Slf4j
@Repository
public class UserRepositoryImpl implements UserRepository {

    @Autowired
    @Qualifier("jooqConfig")
    private Configuration config;

    @Override
    @SuppressWarnings("unchecked")
    public List<User> getUsers(UserQueryForm form) {
        SelectQuery selectQuery = DSL.using(config).selectQuery();
        selectQuery.addFrom(USER);
        selectQuery.addSelect(
                USER.IDTAG,
                USER.PARENTIDTAG,
                USER.EXPIRYDATE,
                USER.INTRANSACTION,
                USER.BLOCKED,
                USER.NOTE
        );

        if (form.isUserIdSet()) {
            selectQuery.addConditions(USER.IDTAG.eq(form.getUserId()));
        }

        if (form.isParentIdSet()) {
            selectQuery.addConditions(USER.PARENTIDTAG.eq(form.getParentId()));
        }

        switch (form.getExpired()) {
            case TRUE:
                selectQuery.addConditions(USER.EXPIRYDATE.lessOrEqual(CustomDSL.utcTimestamp()));
                break;

            case ALL:
            case FALSE:
                selectQuery.addConditions(
                        USER.EXPIRYDATE.isNull().or(USER.EXPIRYDATE.greaterThan(CustomDSL.utcTimestamp()))
                );
                break;
        }

        processBooleanType(selectQuery, USER.INTRANSACTION, form.getInTransaction());
        processBooleanType(selectQuery, USER.BLOCKED, form.getBlocked());

        return selectQuery.fetch().map(new UserMapper());
    }

    @Override
    public Result<UserRecord> getUserRecords() {
        return DSL.using(config)
                  .selectFrom(USER)
                  .fetch();
    }

    @Override
    public Result<UserRecord> getUserRecords(List<String> idTagList) {
        return DSL.using(config)
                  .selectFrom(USER)
                  .where(USER.IDTAG.in(idTagList))
                  .fetch();
    }

    @Override
    public UserRecord getUserRecord(String idTag) {
        return DSL.using(config)
                  .selectFrom(USER)
                  .where(USER.IDTAG.equal(idTag))
                  .fetchOne();
    }

    @Override
    public List<String> getUserIdTags() {
        return DSL.using(config)
                .select(USER.IDTAG)
                .from(USER)
                .fetch(USER.IDTAG);
    }

    @Override
    public List<String> getActiveUserIdTags() {
        return DSL.using(config)
                  .select(USER.IDTAG)
                  .from(USER)
                  .where(USER.INTRANSACTION.isFalse())
                    .and(USER.BLOCKED.isFalse())
                    .and(USER.EXPIRYDATE.isNull().or(USER.EXPIRYDATE.greaterThan(CustomDSL.utcTimestamp())))
                  .fetch(USER.IDTAG);
    }

    @Override
    public List<String> getParentIdTags() {
        return DSL.using(config)
                  .selectDistinct(USER.PARENTIDTAG)
                  .from(USER)
                  .where(USER.PARENTIDTAG.isNotNull())
                  .fetch(USER.PARENTIDTAG);
    }

    @Override
    public String getParentIdtag(String idTag) {
        return DSL.using(config)
                  .select(USER.PARENTIDTAG)
                  .from(USER)
                  .where(USER.IDTAG.eq(idTag))
                  .fetchOne()
                  .value1();
    }

    @Override
    public void addUser(UserForm u) {
        try {
            int count = DSL.using(config)
                           .insertInto(USER,
                                   USER.IDTAG,
                                   USER.PARENTIDTAG,
                                   USER.EXPIRYDATE,
                                   USER.NOTE,
                                   USER.INTRANSACTION,
                                   USER.BLOCKED)
                           .values(u.getIdTag(), u.getParentIdTag(), toDateTime(u.getExpiration()), u.getNote(),
                                   false, false)
                           .onDuplicateKeyIgnore() // Important detail
                           .execute();

            if (count == 0) {
                throw new SteveException("A user with idTag '%s' already exists.", u.getIdTag());
            }
        } catch (DataAccessException e) {
            throw new SteveException("Execution of addUser for idTag '%s' FAILED.", u.getIdTag(), e);
        }
    }

    @Override
    public void updateUser(UserForm u) {
        try {
            DSL.using(config)
               .update(USER)
               .set(USER.PARENTIDTAG, u.getParentIdTag())
               .set(USER.EXPIRYDATE, toDateTime(u.getExpiration()))
               .set(USER.NOTE, u.getNote())
               .set(USER.BLOCKED, u.getBlocked())
               .where(USER.IDTAG.equal(u.getIdTag()))
               .execute();
        } catch (DataAccessException e) {
            throw new SteveException("Execution of updateUser for idTag '%s' FAILED.", u.getIdTag(), e);
        }
    }

    @Override
    public void deleteUser(String idTag) {
        try {
            DSL.using(config)
               .delete(USER)
               .where(USER.IDTAG.equal(idTag))
               .execute();
        } catch (DataAccessException e) {
            throw new SteveException("Execution of deleteUser for idTag '%s' FAILED.", idTag, e);
        }
    }

    private void processBooleanType(SelectQuery selectQuery,
                                    TableField<UserRecord, Boolean> field,
                                    UserQueryForm.BooleanType type) {
        switch (type) {
            case ALL:
                break;

            default:
                selectQuery.addConditions(field.eq(type.getBoolValue()));
        }
    }

    private class UserMapper implements RecordMapper<Record6<String, String, DateTime, Boolean, Boolean, String>, User> {
        @Override
        public User map(Record6<String, String, DateTime, Boolean, Boolean, String> r) {
            return User.builder()
                       .idTag(r.value1())
                       .parentIdTag(r.value2())
                       .expiryDate(humanize(r.value3()))
                       .inTransaction(r.value4())
                       .blocked(r.value5())
                       .note(r.value6())
                       .build();
        }
    }
}