package info.archinnov.achilles.entity.manager;

import info.archinnov.achilles.entity.EntityHelper;
import info.archinnov.achilles.entity.metadata.EntityMeta;
import info.archinnov.achilles.entity.metadata.PropertyMeta;
import info.archinnov.achilles.entity.operations.EntityLoader;
import info.archinnov.achilles.entity.operations.EntityMerger;
import info.archinnov.achilles.entity.operations.EntityPersister;
import info.archinnov.achilles.entity.operations.EntityRefresher;
import info.archinnov.achilles.entity.operations.EntityValidator;
import info.archinnov.achilles.proxy.builder.EntityProxyBuilder;
import info.archinnov.achilles.proxy.interceptor.JpaEntityInterceptor;
import info.archinnov.achilles.validation.Validator;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.FlushModeType;
import javax.persistence.LockModeType;
import javax.persistence.Query;

import me.prettyprint.hector.api.mutation.Mutator;
import net.sf.cglib.proxy.Factory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ThriftEntityManager
 * 
 * Thrift-based Entity Manager for Achilles. This entity manager is perfectly thread-safe and
 * 
 * can be used as a singleton. Entity state is stored in proxy object, which is obviously not
 * 
 * thread-safe.
 * 
 * Internally the ThriftEntityManager relies on Hector API for common operations
 * 
 * @author DuyHai DOAN
 * 
 */
public class ThriftEntityManager implements EntityManager
{
	private static final Logger log = LoggerFactory.getLogger(ThriftEntityManager.class);

	private final Map<Class<?>, EntityMeta<?>> entityMetaMap;

	private EntityPersister persister = new EntityPersister();
	private EntityLoader loader = new EntityLoader();
	private EntityMerger merger = new EntityMerger();
	private EntityRefresher entityRefresher = new EntityRefresher();
	private EntityHelper helper = new EntityHelper();
	private EntityValidator entityValidator = new EntityValidator();
	private EntityProxyBuilder interceptorBuilder = new EntityProxyBuilder();

	ThriftEntityManager(Map<Class<?>, EntityMeta<?>> entityMetaMap) {
		this.entityMetaMap = entityMetaMap;
	}

	/**
	 * Persist an entity. All join entities with CascadeType.PERSIST or CascadeType.ALL
	 * 
	 * will be also persisted, overriding their current state in Cassandra
	 * 
	 * @param entity
	 *            Entity to be persisted
	 */
	@Override
	public void persist(Object entity)
	{
		log.debug("Persisting entity '{}'", entity);

		entityValidator.validateEntity(entity, entityMetaMap);
		if (helper.isProxy(entity))
		{
			throw new IllegalStateException(
					"Then entity is already in 'managed' state. Please use the merge() method instead of persist()");
		}

		EntityMeta<?> entityMeta = this.entityMetaMap.get(entity.getClass());

		this.persister.persist(entity, entityMeta);
	}

	/**
	 * Merge an entity. All join entities with CascadeType.MERGE or CascadeType.ALL
	 * 
	 * will be also merged, updating their current state in Cassandra.
	 * 
	 * Calling merge on a transient entity will persist it and returns a managed
	 * 
	 * instance
	 * 
	 * <strong>Unlike the JPA specs, Achilles returns the same entity passed
	 * 
	 * in parameter if the latter is in managed state. It was designed on purpose
	 * 
	 * so you do not loose the reference of the passed entity. For transient
	 * 
	 * entity, the return value is a new proxy object
	 * 
	 * </strong>
	 * 
	 * @param entity
	 *            Entity to be merged
	 * @return Merged entity or a new proxy object
	 */
	@Override
	public <T> T merge(T entity)
	{
		entityValidator.validateEntity(entity, entityMetaMap);
		Class<?> baseClass = helper.deriveBaseClass(entity);
		EntityMeta<?> entityMeta = this.entityMetaMap.get(baseClass);
		return this.merger.mergeEntity(entity, entityMeta);
	}

	/**
	 * Remove an entity. Join entities are <strong>not</strong> removed
	 * 
	 * CascadeType.REMOVE is not supported as per design to avoid
	 * 
	 * inconsistencies while removing <em>shared</em> join entities
	 * 
	 * You need to remove the join entities manually
	 * 
	 * @param entity
	 *            Entity to be removed
	 */
	@Override
	public void remove(Object entity)
	{
		entityValidator.validateEntity(entity, entityMetaMap);
		if (helper.isProxy(entity))
		{
			Class<?> baseClass = helper.deriveBaseClass(entity);
			EntityMeta<?> entityMeta = this.entityMetaMap.get(baseClass);
			this.persister.remove(entity, entityMeta);

		}
		else
		{
			throw new IllegalStateException(
					"Then entity is not in 'managed' state. Please use the merge() or find() method to load it first");
		}

	}

	/**
	 * Find an entity.
	 * 
	 * @param entityClass
	 *            Entity type
	 * @param primaryKey
	 *            Primary key (Cassandra row key) of the entity to load
	 * @param entity
	 *            Found entity or null if no entity is found
	 */
	@SuppressWarnings("unchecked")
	@Override
	public <T> T find(Class<T> entityClass, Object primaryKey)
	{
		Validator.validateNotNull(entityClass, "Entity class should not be null");
		Validator.validateNotNull(primaryKey, "Entity primaryKey should not be null");

		EntityMeta<Serializable> entityMeta = (EntityMeta<Serializable>) this.entityMetaMap
				.get(entityClass);

		T entity = (T) this.loader.load(entityClass, (Serializable) primaryKey, entityMeta);

		if (entity != null)
		{
			entity = (T) this.interceptorBuilder.build(entity, entityMeta);
		}

		return entity;
	}

	/**
	 * Find an entity. Works exactly as find(Class<T> entityClass, Object primaryKey)
	 * 
	 * @param entityClass
	 *            Entity type
	 * @param primaryKey
	 *            Primary key (Cassandra row key) of the entity to load
	 * @param entity
	 *            Found entity or null if no entity is found
	 */
	@Override
	public <T> T getReference(Class<T> entityClass, Object primaryKey)
	{
		return this.find(entityClass, primaryKey);
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public void flush()
	{
		throw new UnsupportedOperationException("This operation is not supported for Cassandra");
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public void setFlushMode(FlushModeType flushMode)
	{
		throw new UnsupportedOperationException("This operation is not supported for Cassandra");

	}

	/**
	 * Always return FlushModeType.AUTO
	 */
	@Override
	public FlushModeType getFlushMode()
	{
		return FlushModeType.AUTO;
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public void lock(Object entity, LockModeType lockMode)
	{
		throw new UnsupportedOperationException(
				"This operation is not supported for this Cassandra");
	}

	/**
	 * Refresh an entity. All join entities with CascadeType.REFRESH or CascadeType.ALL
	 * 
	 * will be also refreshed from Cassandra.
	 * 
	 * @param entity
	 *            Entity to be refreshed
	 */
	@Override
	public void refresh(Object entity)
	{

		if (!helper.isProxy(entity))
		{
			throw new IllegalStateException("The entity " + entity + " is not in 'managed' state");
		}
		else
		{
			entityRefresher.refresh(entity, entityMetaMap);
		}
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public void clear()
	{
		throw new UnsupportedOperationException(
				"This operation is not supported for this Cassandra");
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public boolean contains(Object entity)
	{
		throw new UnsupportedOperationException(
				"This operation is not supported for this Cassandra");
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public Query createQuery(String qlString)
	{
		throw new UnsupportedOperationException(
				"This operation is not supported for this Cassandra");
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public Query createNamedQuery(String name)
	{
		throw new UnsupportedOperationException(
				"This operation is not supported for this Entity Manager");
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public Query createNativeQuery(String sqlString)
	{
		throw new UnsupportedOperationException(
				"This operation is not supported for this Cassandra");
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@SuppressWarnings("rawtypes")
	@Override
	public Query createNativeQuery(String sqlString, Class resultClass)
	{
		throw new UnsupportedOperationException(
				"This operation is not supported for this Cassandra");
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public Query createNativeQuery(String sqlString, String resultSetMapping)
	{
		throw new UnsupportedOperationException(
				"This operation is not supported for this Cassandra");
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public void joinTransaction()
	{
		throw new UnsupportedOperationException(
				"This operation is not supported for this Cassandra");

	}

	/**
	 * @return the ThriftEntityManager instance itself
	 */
	@Override
	public Object getDelegate()
	{
		return this;
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public void close()
	{
		throw new UnsupportedOperationException(
				"This operation is not supported for this Cassandra");

	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public boolean isOpen()
	{
		throw new UnsupportedOperationException(
				"This operation is not supported for this Cassandra");
	}

	/**
	 * Not supported operation. Will throw UnsupportedOperationException
	 */
	@Override
	public EntityTransaction getTransaction()
	{
		throw new UnsupportedOperationException(
				"This operation is not supported for this Cassandra");
	}

	/**
	 * Start a batch session using a Hector mutator.
	 * 
	 * All insertions done on <strong>WideMap</strong> fields of the entity
	 * 
	 * will be batched through the mutator.
	 * 
	 * A new mutator is created for each join <strong>WideMap</strong> entity
	 * 
	 * The batch does not affect dirty checking of other fields.
	 * 
	 * It only works on <strong>WideMap</strong> fields
	 */
	@SuppressWarnings(
	{
			"rawtypes",
			"unchecked"
	})
	public void startBatch(Object entity)
	{
		Mutator<?> mutator;
		if (!helper.isProxy(entity))
		{
			throw new IllegalStateException(
					"The entity is not in 'managed' state. Please merge it before starting a batch");
		}
		else
		{
			Class<?> baseClass = helper.deriveBaseClass(entity);
			EntityMeta<?> entityMeta = this.entityMetaMap.get(baseClass);

			Map<String, Mutator<?>> mutatorMap = new HashMap<String, Mutator<?>>();

			for (PropertyMeta<?, ?> propertyMeta : entityMeta.getPropertyMetas().values())
			{
				if (propertyMeta.type().isJoinColumn())
				{
					mutatorMap.put(propertyMeta.getPropertyName(), propertyMeta.getJoinProperties()
							.getEntityMeta().getEntityDao().buildMutator());
				}
			}

			mutator = entityMeta.getEntityDao().buildMutator();

			Factory proxy = (Factory) entity;
			JpaEntityInterceptor<?> interceptor = (JpaEntityInterceptor<?>) proxy.getCallback(0);
			interceptor.setMutator((Mutator) mutator);
			interceptor.setMutatorMap(mutatorMap);
		}
	}

	/**
	 * End an existing batch and flush all the mutators.
	 * 
	 * All join entities will be flushed through their own mutator
	 * 
	 */
	public void endBatch(Object entity)
	{
		if (!helper.isProxy(entity))
		{
			throw new IllegalStateException("The entity is not in 'managed' state.");
		}
		else
		{
			Factory proxy = (Factory) entity;
			JpaEntityInterceptor<?> interceptor = (JpaEntityInterceptor<?>) proxy.getCallback(0);

			Mutator<?> mutator = interceptor.getMutator();

			if (mutator != null)
			{
				mutator.execute();
			}
			for (Mutator<?> joinMutator : interceptor.getMutatorMap().values())
			{
				if (joinMutator != null)
				{
					joinMutator.execute();
				}
			}
		}
	}
}