package org.codehaus.groovy.grails.plugins.orm.auditable
/**
 * @author shawn hartsock
 *
 * Grails Hibernate Interceptor for logging
 * saves, updates, deletes and acting on
 * individual properties changes... and
 * delegating calls back to the Domain Class
 *
 * This class is based on the post at
 * http://www.hibernate.org/318.html
 *
 * 2008-04-17 created initial version 0.1
 * 2008-04-21 changes for version 0.2 include simpler events
 *  ... config file ... removed 'onUpdate' event.
 * 2008-06-04 added ignore fields feature
 * 2009-07-04 fetches its own session from sessionFactory to avoid transaction munging
 * 2009-09-05 getActor as a closure to allow developers to supply their own security plugins
 * 2009-09-25 rewrite.
 * 2009-10-04 preparing beta release
 * 2010-10-13 add a transactional config so transactions can be manually toggled by a user OR automatically disabled for testing
 * 2012-01-20 Audit Logging rewrite for Grails 2.0
 */

import org.hibernate.HibernateException;
import org.hibernate.cfg.Configuration;
import org.hibernate.event.PreDeleteEvent;
import org.hibernate.event.PostInsertEvent;
import org.hibernate.event.PostUpdateEvent;
import org.springframework.web.context.request.RequestContextHolder;
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.hibernate.Session
import grails.util.Environment
import org.codehaus.groovy.grails.commons.ConfigurationHolder

public class AuditLogListener extends AbstractAuditEventListener implements AuditEventListener {
    public static Long TRUNCATE_LENGTH = 255
    Long truncateLength

    def sessionFactory

    void init() {
        if (Environment.getCurrent() != Environment.PRODUCTION && ConfigurationHolder.config.auditLog?.transactional == null) {
            config.transactional = false
        }

        if(config?.actorClosure)
            this.setActorClosure(config.actorClosure)

        log.info AuditLogListener.class.getCanonicalName() + " initializing AuditLogListener... "
        if (!truncateLength) {
            truncateLength = new Long(TRUNCATE_LENGTH)
        }
        else {
            log.debug "truncate length set to ${truncateLength}"
        }
    }

    String getActor() {
        def actor = null
        try {
            def attr = RequestContextHolder?.getRequestAttributes()
            def session = attr?.session
            actor = ( session ) ? actorClosure?.call(attr, session) : 'system'
        } catch (Exception ex) {
            log.error "The auditLog.actorClosure threw this exception: ${ex.message}"
            ex.printStackTrace()
            log.error "The auditLog.actorClosure will be disabled now."
            actorClosure = null
        }
        return actor?.toString()
    }

    def getUri() {
        def attr = RequestContextHolder?.getRequestAttributes()
        return (attr?.currentRequest?.uri?.toString()) ?: null
    }

    // returns true for auditable entities.
    def isAuditableEntity(event) {
        if (event && event.getEntity()) {
            def entity = event.getEntity()
            if (entity.metaClass.hasProperty(entity, 'auditable') && entity.'auditable') {
                return true
            }
        }
        return false
    }

    /**
     *  we allow user's to specify
     *       static auditable = [handlersOnly:true]
     *  if they don't want us to log events for them and instead have their own plan.
     */
    boolean callHandlersOnly(entity) {
        if (entity?.metaClass?.hasProperty(entity, 'auditable') && entity?.'auditable') {
            if (entity.auditable instanceof java.util.Map &&
                    entity.auditable.containsKey('handlersOnly')) {
                return (entity.auditable['handlersOnly']) ? true : false
            }
            return false
        }
        return false
    }

    /**
     * The default ignore field list is:  ['version','lastUpdated'] if you want
     * to provide your own ignore list do so by specifying the ignore list like so:
     *
     *   static auditable = [ignore:['version','lastUpdated','myField']]
     *
     * ... if instead you really want to trigger on version and lastUpdated changes you
     * may specify an empty ignore list ... like so ...
     *
     *   static auditable = [ignore:[]]
     *
     */
    List ignoreList(entity) {
        def ignore = ['version', 'lastUpdated']
        if (entity?.metaClass?.hasProperty(entity, 'auditable')) {
            if (entity.auditable instanceof java.util.Map && entity.auditable.containsKey('ignore')) {
                log.debug "found an ignore list one this entity ${entity.getClass()}"
                def list = entity.auditable['ignore']
                if (list instanceof java.util.List) {
                    ignore = list
                }
            }
        }
        return ignore
    }

    def getEntityId(event) {
        if (event && event.entity) {
            def entity = event.getEntity()
            if (entity.metaClass.hasProperty(entity, 'id') && entity.'id') {
                return entity.id.toString()
            }
            else {
                // trying complex entity resolution
                return event.getPersister().hasIdentifierProperty() ? event.getPersister().getIdentifier(event.getEntity(), event.getPersister().guessEntityMode(event.getEntity())) : null;
            }
        }
        return null
    }

    /**
     * We must use the preDelete event if we want to capture
     * what the old object was like.
     */
    public boolean onPreDelete(final PreDeleteEvent event) {
        try {
            def audit = isAuditableEntity(event)
            String[] names = event.getPersister().getPropertyNames()
            Object[] state = event.getDeletedState()
            def map = makeMap(names, state)
            if (audit && !callHandlersOnly(event.getEntity())) {
                def entity = event.getEntity()
                def entityName = entity.getClass().getName()
                def entityId = getEntityId(event)
                emitAuditEvent(null, map, null, entityId, 'DELETE', entityName)
            }
            if (audit) {
                executeHandler(event, 'onDelete', map, null)
            }
        } catch (HibernateException e) {
            log.error "Audit Plugin unable to process DELETE event"
            e.printStackTrace()
        }
        return false
    }

    /**
     * I'm using the post insert event here instead of the pre-insert
     * event so that I can log the id of the new entity after it
     * is saved. That does mean the the insert event handlers
     * can't work the way we want... but really it's the onChange
     * event handler that does the magic for us.
     */
    public void onPostInsert(final PostInsertEvent event) {
        try {
            def audit = isAuditableEntity(event)
            String[] names = event.getPersister().getPropertyNames()
            Object[] state = event.getState()
            def map = makeMap(names, state)
            if (audit && !callHandlersOnly(event.getEntity())) {
                log.debug "logging changes for auditable entity"
                def entity = event.getEntity()
                def entityName = entity.getClass().getName()
                def entityId = getEntityId(event)
                emitAuditEvent(map, null, null, entityId, 'INSERT', entityName)
            }
            if (audit) {
                log.debug "calling event handlers for ${event.getEntity().getClass().getCanonicalName()}"
                executeHandler(event, 'onSave', null, map)
            }
        } catch (HibernateException e) {
            log.error "Audit Plugin unable to process INSERT event"
            e.printStackTrace()
        }
        log.trace "... onPostInsert is finished."
        return;
    }

    private def makeMap(String[] names, Object[] state) {
        def map = [:]
        for (int ii = 0; ii < names.length; ii++) {
            map[names[ii]] = state[ii]
        }
        return map
    }

    /**
     * Now we get fancy. Here we want to log changes...
     * specifically we want to know what property changed,
     * when it changed. And what the differences were.
     *
     * This works better from the onPreUpdate event handler
     * but for some reason it won't execute right for me.
     * Instead I'm doing a rather complex mapping to build
     * a pair of state HashMaps that represent the old and
     * new states of the object.
     *
     * The old and new states are passed to the object's
     * 'onChange' event handler. So that a user can work
     * with both sets of values.
     *
     * Needs complex type testing BTW.
     */
    public void onPostUpdate(final PostUpdateEvent event) {
        if (isAuditableEntity(event)) {
            log.trace "${event.getClass()} onChange handler has been called"
            onChange(event)
        }
    }

    /**
     * Prevent infinite loops of change logging by trapping
     * non-significant changes. Occasionally you can set up
     * a change handler that will create a "trivial" object
     * change that you don't want to trigger another change
     * event. So this feature uses the ignore parameter
     * to provide a list of fields for onChange to ignore.
     */
    private boolean significantChange(entity, oldMap, newMap) {
        def ignore = ignoreList(entity)
        ignore?.each({key ->
            oldMap.remove(key)
            newMap.remove(key)
        })
        boolean changed = false
        oldMap.each({k, v ->
            if (v != newMap[k]) {
                changed = true
            }
        })
        return changed
    }

    /**
     * only if this is an auditable entity
     */
    private void onChange(final PostUpdateEvent event) {
        def entity = event.getEntity()
        def entityName = entity.getClass().getName()
        def entityId = event.getId()

        // object arrays representing the old and new state
        def oldState = event.getOldState()
        def newState = event.getState()

        def nameMap = event.getPersister().getPropertyNames()
        def oldMap = [:]
        def newMap = [:]

        if (nameMap) {
            for (int ii = 0; ii < newState.length; ii++) {
                if (nameMap[ii]) {
                    if (oldState) oldMap[nameMap[ii]] = oldState[ii]
                    if (newState) newMap[nameMap[ii]] = newState[ii]
                }
            }
        }

        if (!significantChange(entity, oldMap, newMap)) {
            return
        }

        // allow user's to over-ride whether you do auditing for them.
        if (!callHandlersOnly(event.getEntity())) {
            emitAuditEvent(newMap, oldMap, event, entityId, 'UPDATE', entityName)
        }
        /**
         * Hibernate only saves changes when there are changes.
         * That means the onUpdate event was the same as the
         * onChange with no parameters. I've eliminated onUpdate.
         */
        executeHandler(event, 'onChange', oldMap, newMap)
        return
    }

    public void initialize(final Configuration config) {
        // TODO at some point we will participate in Hibernate configuration
        return
    }

    /**
     * Leans heavily on the "toString()" of a property
     * ... this feels crufty... should be tighter...
     */
    def emitAuditEvent(newMap, oldMap, parentObject, persistedObjectId, eventName, className) {
        final AuditEvent event = new AuditEvent(
                actor: this.getActor(),
                uri: this.getUri(),
                newMap:newMap,
                oldMap:oldMap,
                parentObject:parentObject,
                persistedObjectId:persistedObjectId,
                eventName:eventName,
                className:className
        );        
        
        log.trace "logging changes... "
        AuditLogEvent audit = null
        def persistedObjectVersion = (newMap?.version) ?: oldMap?.version
        if (newMap) newMap.remove('version')
        if (oldMap) oldMap.remove('version')
        if (newMap && oldMap) {
            log.trace "there are new and old values to log"
            newMap.each({key, val ->
                if (val != oldMap[key]) {
                    audit = new AuditLogEvent(
                            actor: this.getActor(),
                            uri: this.getUri(),
                            className: className,
                            eventName: eventName,
                            persistedObjectId: persistedObjectId?.toString(),
                            persistedObjectVersion: persistedObjectVersion?.toLong(),
                            propertyName: key,
                            oldValue: truncate(oldMap[key]),
                            newValue: truncate(newMap[key]),
                    )
                    saveAuditLog(audit)
                }
            })
        }
        else if (newMap && verbose) {
            log.trace "there are new values and logging is verbose ... "
            newMap.each({key, val ->
                audit = new AuditLogEvent(
                        actor: this.getActor(),
                        uri: this.getUri(),
                        className: className,
                        eventName: eventName,
                        persistedObjectId: persistedObjectId?.toString(),
                        persistedObjectVersion: persistedObjectVersion?.toLong(),
                        propertyName: key,
                        oldValue: null,
                        newValue: truncate(val),
                )
                saveAuditLog(audit)
            })
        }
        else if (oldMap && verbose) {
            log.trace "there is only an old map of values available and logging is set to verbose... "
            oldMap.each({key, val ->
                audit = new AuditLogEvent(
                        actor: this.getActor(),
                        uri: this.getUri(),
                        className: className,
                        eventName: eventName,
                        persistedObjectId: persistedObjectId?.toString(),
                        persistedObjectVersion: persistedObjectVersion?.toLong(),
                        propertyName: key,
                        oldValue: truncate(val),
                        newValue: null
                )
                saveAuditLog(audit)
            })
        }
        else {
            log.trace "creating a basic audit logging event object."
            audit = new AuditLogEvent(
                    actor: this.getActor(),
                    uri: this.getUri(),
                    className: className,
                    eventName: eventName,
                    persistedObjectId: persistedObjectId?.toString(),
                    persistedObjectVersion: persistedObjectVersion?.toLong()
            )
            saveAuditLog(audit)
        }
        return
    }

    String truncate(final obj) {
        truncate(obj, truncateLength.toInteger())
    }

    String truncate(final obj, int max) {
        log.trace "trimming object's string representation based on ${max} characters."
        def str = obj?.toString()?.trim()
        return (str?.length() > max) ? str?.substring(0, max) : str
    }

    /**
     * This calls the handlers based on what was passed in to it.
     */
    def executeHandler(event, handler, oldState, newState) {
        log.trace "calling execute handler ... "
        def entity = event.getEntity()
        if (isAuditableEntity(event) && entity.metaClass.hasProperty(entity, handler)) {
            log.trace "entity was auditable and had a handler property ${handler}"
            if (oldState && newState) {
                log.trace "there was both an old state and a new state"
                if (entity."${handler}".maximumNumberOfParameters == 2) {
                    log.trace "there are two parameters to the handler so I'm sending old and new value maps"
                    entity."${handler}"(oldState, newState)
                }
                else {
                    log.trace "only one parameter on the closure I'm sending oldMap and newMap as part of a Map parameter"
                    entity."${handler}"([oldMap: oldState, newMap: newState])
                }
            }
            else if (oldState) {
                log.trace "sending old state into ${handler}"
                entity."${handler}"(oldState)
            }
            else if (newState) {
                log.trace "sending new state into ${handler}"
                entity."${handler}"(newState)
            }
        }
        log.trace "... execute handler is finished."
    }

    /**
     * Performs special logging and save actions for AuditLogEvent class. You cannot use
     * the GORM save features to persist this class on transactional databases due to timing
     * conflicts with the session. Due to problems in this routine on some databases
     * this closure has comprehensive trace logging in it for your diagnostics.
     *
     * It has also been written as a closure for your sake so that you may over-ride the
     * save closure with your own code (should your particular database not work with this code)
     * you may over-ride the definition of this closure using ... TODO allow over-ride via config
     *
     * To debug in Config.groovy set:
     *    log4j.debug 'org.codehaus.groovy.grails.plugins.orm.auditable.AuditLogListener'
     * or
     *    log4j.trace 'org.codehaus.groovy.grails.plugins.orm.auditable.AuditLogListener'
     *
     * SEE: GRAILSPLUGINS-391
     */
    def saveAuditLog = { AuditLogEvent audit ->
        audit.with {
            dateCreated = new Date()
            lastUpdated = new Date()
        }
        log.info audit
        try {
            // NOTE: you simply cannot use the session that GORM is using for some
            // audit log events. That's because some audit events occur *after* a
            // transaction has committed. Late session save actions can invalidate
            // sessions or you can essentially roll-back your audit log saves. This is
            // why you have to open your own session and transaction on some
            // transactional databases and not others.
            Session session = sessionFactory.openSession()
            log.trace "opened new session for audit log persistence"
            def trans = null
            if (transactional) {
                trans = session.beginTransaction()
                log.trace " + began transaction "
            }
            def saved = session.merge(audit)
            log.debug " + saved log entry id:'${saved.id}'."
            session.flush()
            log.trace " + flushed session"
            if (transactional) {
                trans?.commit()
                log.trace " + committed transaction"
            }
            session.close()
            log.trace "session closed"
        } catch (org.hibernate.HibernateException ex) {
            log.error "AuditLogEvent save has failed!"
            log.error ex.message
            log.error audit.errors
        }
        return
    }

    @Override
    void setConfig(AuditEventListenerConfig config) {
        this.config = config
    }
}