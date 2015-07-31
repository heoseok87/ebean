package com.avaje.ebeaninternal.server.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.avaje.ebeaninternal.api.ManyWhereJoins;
import com.avaje.ebeaninternal.api.PropertyJoin;
import com.avaje.ebeaninternal.api.SpiQuery;
import com.avaje.ebeaninternal.api.SpiQuery.Type;
import com.avaje.ebeaninternal.server.core.OrmQueryRequest;
import com.avaje.ebeaninternal.server.deploy.BeanDescriptor;
import com.avaje.ebeaninternal.server.deploy.BeanProperty;
import com.avaje.ebeaninternal.server.deploy.BeanPropertyAssoc;
import com.avaje.ebeaninternal.server.deploy.BeanPropertyAssocMany;
import com.avaje.ebeaninternal.server.deploy.BeanPropertyAssocOne;
import com.avaje.ebeaninternal.server.deploy.InheritInfo;
import com.avaje.ebeaninternal.server.deploy.TableJoin;
import com.avaje.ebeaninternal.server.el.ElPropertyValue;
import com.avaje.ebeaninternal.server.querydefn.OrmQueryDetail;
import com.avaje.ebeaninternal.server.querydefn.OrmQueryProperties;

/**
 * Factory for SqlTree.
 */
public class SqlTreeBuilder {

  private static final Logger logger = LoggerFactory.getLogger(SqlTreeBuilder.class);

  private final SpiQuery<?> query;

  private final BeanDescriptor<?> desc;

  private final OrmQueryDetail queryDetail;

  private final StringBuilder summary = new StringBuilder();

  private final CQueryPredicates predicates;

  private final boolean subQuery;

  /**
   * Property if resultSet contains master and detail rows.
   */
  private BeanPropertyAssocMany<?> manyProperty;

  private final SqlTreeAlias alias;

  private final DefaultDbSqlContext ctx;

  private final HashSet<String> selectIncludes = new HashSet<String>();

  private final ManyWhereJoins manyWhereJoins;

  private final TableJoin includeJoin;

  private final boolean rawSql;

  /**
   * rawNoId true if the RawSql does not include the @Id property
   */
  private final boolean rawNoId;

  private final boolean disableLazyLoad;

  private SqlTreeNode rootNode;

  /**
   * Construct for RawSql query.
   */
  public SqlTreeBuilder(OrmQueryRequest<?> request, CQueryPredicates predicates, OrmQueryDetail queryDetail, boolean rawNoId) {

    this.rawSql = true;
    this.desc = request.getBeanDescriptor();
    this.rawNoId = rawNoId;
    this.disableLazyLoad = request.getQuery().isDisableLazyLoading();
    this.query = null;
    this.subQuery = false;
    this.queryDetail = queryDetail;
    this.predicates = predicates;

    this.includeJoin = null;
    this.manyWhereJoins = null;
    this.alias = null;
    this.ctx = null;
  }

  /**
   * The predicates are used to determine if 'extra' joins are required to
   * support the where and/or order by clause. If so these extra joins are added
   * to the root node.
   */
  public SqlTreeBuilder(String tableAliasPlaceHolder, String columnAliasPrefix,
                        OrmQueryRequest<?> request, CQueryPredicates predicates, CQueryHistorySupport historySupport) {

    this.rawSql = false;
    this.rawNoId = false;
    this.desc = request.getBeanDescriptor();
    this.query = request.getQuery();
    this.disableLazyLoad = query.isDisableLazyLoading();
    this.subQuery = Type.SUBQUERY.equals(query.getType()) || Type.ID_LIST.equals(query.getType());
    this.includeJoin = query.getIncludeTableJoin();
    this.manyWhereJoins = query.getManyWhereJoins();
    this.queryDetail = query.getDetail();

    this.predicates = predicates;
    this.alias = new SqlTreeAlias(request.getQuery().getAlias() == null ? request.getBeanDescriptor().getBaseTableAlias() : request.getQuery().getAlias());
    this.ctx = new DefaultDbSqlContext(alias, tableAliasPlaceHolder, columnAliasPrefix, !subQuery, historySupport);
  }

  /**
   * Build based on the includes and using the BeanJoinTree.
   */
  public SqlTree build() {

    summary.append(desc.getName());

    // build the appropriate chain of SelectAdapter's
    buildRoot(desc);

    // build the actual String
    String selectSql = null;
    String fromSql = null;
    String inheritanceWhereSql = null;
    BeanProperty[] encryptedProps = null;
    if (!rawSql) {
      selectSql = buildSelectClause();
      fromSql = buildFromClause();
      inheritanceWhereSql = buildWhereClause();
      encryptedProps = ctx.getEncryptedProps();
    }

    return new SqlTree(summary.toString(), rootNode, selectSql, fromSql, inheritanceWhereSql, encryptedProps,
        manyProperty, queryDetail.getIncludes());
  }

  private String buildSelectClause() {

    if (rawSql) {
      return "Not Used";
    }
    rootNode.appendSelect(ctx, subQuery);

    String selectSql = ctx.getContent();

    // trim off the first comma
    if (selectSql.length() >= SqlTreeNode.COMMA.length()) {
      selectSql = selectSql.substring(SqlTreeNode.COMMA.length());
    }

    return selectSql;
  }

  private String buildWhereClause() {

    if (rawSql) {
      return "Not Used";
    }
    rootNode.appendWhere(ctx);
    return ctx.getContent();
  }

  private String buildFromClause() {

    if (rawSql) {
      return "Not Used";
    }
    rootNode.appendFrom(ctx, SqlJoinType.AUTO);
    return ctx.getContent();
  }

  private void buildRoot(BeanDescriptor<?> desc) {

    rootNode = buildSelectChain(null, null, desc, null);

    if (!rawSql) {
      alias.addJoin(queryDetail.getIncludes(), desc);
      alias.addJoin(predicates.getPredicateIncludes(), desc);
      alias.addManyWhereJoins(manyWhereJoins.getPropertyNames());

      // build set of table alias
      alias.buildAlias();

      predicates.parseTableAlias(alias);
    }
  }

  /**
   * Recursively build the query tree depending on what leaves in the tree
   * should be included.
   */
  private SqlTreeNode buildSelectChain(String prefix, BeanPropertyAssoc<?> prop,
                                       BeanDescriptor<?> desc, List<SqlTreeNode> joinList) {

    List<SqlTreeNode> myJoinList = new ArrayList<SqlTreeNode>();

    BeanPropertyAssocOne<?>[] ones = desc.propertiesOne();
    for (int i = 0; i < ones.length; i++) {
      String propPrefix = SplitName.add(prefix, ones[i].getName());
      if (isIncludeBean(propPrefix)) {
        selectIncludes.add(propPrefix);
        buildSelectChain(propPrefix, ones[i], ones[i].getTargetDescriptor(), myJoinList);
      }
    }

    BeanPropertyAssocMany<?>[] manys = desc.propertiesMany();
    for (int i = 0; i < manys.length; i++) {
      String propPrefix = SplitName.add(prefix, manys[i].getName());
      if (isIncludeMany(propPrefix, manys[i])) {
        selectIncludes.add(propPrefix);
        buildSelectChain(propPrefix, manys[i], manys[i].getTargetDescriptor(), myJoinList);
      }
    }

    if (prefix == null && !rawSql) {
      addManyWhereJoins(myJoinList);
    }

    SqlTreeNode selectNode = buildNode(prefix, prop, desc, myJoinList);
    if (joinList != null) {
      joinList.add(selectNode);
    }
    return selectNode;
  }

  /**
   * Add joins used to support where clause predicates on 'many' properties.
   * <p>
   * These joins are effectively independent of any fetch joins on 'many'
   * properties.
   * </p>
   */
  private void addManyWhereJoins(List<SqlTreeNode> myJoinList) {

    Collection<PropertyJoin> includes = manyWhereJoins.getPropertyJoins();
    for (PropertyJoin joinProp : includes) {
      BeanPropertyAssoc<?> beanProperty = (BeanPropertyAssoc<?>) desc.getBeanPropertyFromPath(joinProp.getProperty());
      SqlTreeNodeManyWhereJoin nodeJoin = new SqlTreeNodeManyWhereJoin(joinProp.getProperty(), beanProperty, joinProp.getSqlJoinType());
      myJoinList.add(nodeJoin);
    }
  }

  private SqlTreeNode buildNode(String prefix, BeanPropertyAssoc<?> prop, BeanDescriptor<?> desc, List<SqlTreeNode> myList) {

    OrmQueryProperties queryProps = queryDetail.getChunk(prefix, false);

    SqlTreeProperties props = getBaseSelect(desc, queryProps);

    if (prefix == null) {
      buildExtraJoins(desc, myList);

      // Optional many property for lazy loading query
      BeanPropertyAssocMany<?> lazyLoadMany = (query == null) ? null : query.getLazyLoadForParentsProperty();
      boolean withId = !rawNoId && !subQuery && (query == null || !query.isDistinct());
      return new SqlTreeNodeRoot(desc, props, myList, withId, includeJoin, lazyLoadMany, SpiQuery.TemporalMode.of(query), disableLazyLoad);

    } else if (prop instanceof BeanPropertyAssocMany<?>) {
      return new SqlTreeNodeManyRoot(prefix, (BeanPropertyAssocMany<?>) prop, props, myList, disableLazyLoad);

    } else {
      return new SqlTreeNodeBean(prefix, prop, props, myList, true, disableLazyLoad);
    }
  }

  /**
   * Build extra joins to support properties used in where clause but not
   * already in select clause.
   */
  private void buildExtraJoins(BeanDescriptor<?> desc, List<SqlTreeNode> myList) {

    if (rawSql) {
      return;
    }

    Set<String> predicateIncludes = predicates.getPredicateIncludes();

    if (predicateIncludes == null) {
      return;
    }

    // Note includes - basically means joins.
    // The selectIncludes is the set of joins that are required to support
    // the 'select' part of the query. We may need to add other joins to
    // support the predicates or order by clauses.

    // remove ManyWhereJoins from the predicateIncludes
    predicateIncludes.removeAll(manyWhereJoins.getPropertyNames());
    predicateIncludes.addAll(predicates.getOrderByIncludes());

    // look for predicateIncludes that are not in selectIncludes and add
    // them as extra joins to the query
    IncludesDistiller extraJoinDistill = new IncludesDistiller(desc, selectIncludes, predicateIncludes);

    Collection<SqlTreeNodeExtraJoin> extraJoins = extraJoinDistill.getExtraJoinRootNodes();
    if (!extraJoins.isEmpty()) {
      // add extra joins required to support predicates
      // and/or order by clause
      for (SqlTreeNodeExtraJoin extraJoin : extraJoins) {
        myList.add(extraJoin);
        if (extraJoin.isManyJoin()) {
          // as we are now going to join to the many then we need
          // to add the distinct to the sql query to stop duplicate
          // rows...
          query.setSqlDistinct(true);
        }
      }
    }
  }

  /**
   * A subQuery has slightly different rules in that it just generates SQL (into
   * the where clause) and its properties are not required to read the resultSet
   * etc.
   * <p>
   * This means it can included individual properties of an embedded bean.
   * </p>
   */
  private void addPropertyToSubQuery(SqlTreeProperties selectProps, BeanDescriptor<?> desc, String propName) {

    BeanProperty p = desc.findBeanProperty(propName);
    if (p == null) {
      logger.error("property [" + propName + "]not found on " + desc + " for query - excluding it.");

    } else if (p instanceof BeanPropertyAssoc<?> && p.isEmbedded()) {
      // if the property is embedded we need to lookup the real column name
      int pos = propName.indexOf(".");
      if (pos > -1) {
        String name = propName.substring(pos + 1);
        p = ((BeanPropertyAssoc<?>) p).getTargetDescriptor().findBeanProperty(name);
      }
    }

    selectProps.add(p);
  }

  private void addProperty(SqlTreeProperties selectProps, BeanDescriptor<?> desc,
                           OrmQueryProperties queryProps, String propName) {

    if (subQuery) {
      addPropertyToSubQuery(selectProps, desc, propName);
      return;
    }

    int basePos = propName.indexOf('.');
    if (basePos > -1) {
      // property on an embedded bean. Embedded beans do not yet
      // support being partially populated so we include the
      // 'base' property and make sure we only do that once
      String baseName = propName.substring(0, basePos);

      // make sure we only included the base/embedded bean once
      if (!selectProps.containsProperty(baseName)) {
        BeanProperty p = desc.findBeanProperty(baseName);
        if (p == null) {
          logger.error("property [" + propName + "] not found on " + desc + " for query - excluding it.");

        } else if (p.isEmbedded()) {
          // add the embedded bean (and effectively
          // all its properties)
          selectProps.add(p);

        } else {
          String m = "property [" + p.getFullBeanName() + "] expected to be an embedded bean for query - excluding it.";
          logger.error(m);
        }
      }

    } else {
      // find the property including searching the
      // sub class hierarchy if required
      BeanProperty p = desc.findBeanProperty(propName);
      if (p == null) {
        logger.error("property [" + propName + "] not found on " + desc + " for query - excluding it.");
        p = desc.findBeanProperty("id");
        selectProps.add(p);

      } else if (p.isId()) {
        // do not bother to include id for normal queries as the
        // id is always added (except for subQueries)

      } else if (p instanceof BeanPropertyAssoc<?>) {
        // need to check if this property should be
        // excluded. This occurs when this property is
        // included as a bean join. With a bean join
        // the property should be excluded as the bean
        // join has its own node in the SqlTree.
        if (!queryProps.isIncludedBeanJoin(p.getName())) {
          // include the property... which basically
          // means include the foreign key column(s)
          selectProps.add(p);
        }
      } else {
        selectProps.add(p);
      }
    }
  }

  private SqlTreeProperties getBaseSelectPartial(BeanDescriptor<?> desc, OrmQueryProperties queryProps) {

    SqlTreeProperties selectProps = new SqlTreeProperties();
    selectProps.setReadOnly(queryProps.isReadOnly());

    // add properties in the order in which they appear
    // in the query. Gives predictable sql/properties for
    // use with SqlSelect type queries.

    // Also note that this can include transient properties.
    // This makes sense for transient properties used to
    // hold sum() count() type values (with SqlSelect)
    for (String propName : queryProps.getSelectProperties()) {
      if (propName.length() > 0) {
        addProperty(selectProps, desc, queryProps, propName);
      }
    }

    return selectProps;
  }

  private SqlTreeProperties getBaseSelect(BeanDescriptor<?> desc, OrmQueryProperties queryProps) {

    boolean partial = queryProps != null && !queryProps.allProperties();
    if (partial) {
      return getBaseSelectPartial(desc, queryProps);
    }

    SqlTreeProperties selectProps = new SqlTreeProperties();
    selectProps.setAllProperties(true);

    // normal simple properties of the bean
    selectProps.add(desc.propertiesBaseScalar());
    selectProps.add(desc.propertiesBaseCompound());
    selectProps.add(desc.propertiesEmbedded());

    BeanPropertyAssocOne<?>[] propertiesOne = desc.propertiesOne();
    for (int i = 0; i < propertiesOne.length; i++) {
      if (queryProps != null && queryProps.isIncludedBeanJoin(propertiesOne[i].getName())) {
        // if it is a joined bean... then don't add the property
        // as it will have its own entire Node in the SqlTree
      } else {
        selectProps.add(propertiesOne[i]);
      }
    }

    selectProps.setTableJoins(desc.tableJoins());

    InheritInfo inheritInfo = desc.getInheritInfo();
    if (inheritInfo != null) {
      // add sub type properties
      inheritInfo.addChildrenProperties(selectProps);

    }
    return selectProps;
  }

  /**
   * Return true if this many node should be included in the query.
   */
  private boolean isIncludeMany(String propName, BeanPropertyAssocMany<?> manyProp) {

    if (queryDetail.isJoinsEmpty()) {
      return false;
    }

    if (queryDetail.includes(propName)) {

      if (manyProperty != null) {
        // only one many associated allowed to be included in fetch
        if (logger.isDebugEnabled()) {
          logger.debug("Not joining [" + propName + "] as already joined to a Many[" + manyProperty + "].");
        }
        return false;
      }

      manyProperty = manyProp;
      summary.append(" +many:").append(propName);
      return true;
    }
    return false;
  }

  /**
   * Test to see if we are including this node into the query.
   * <p>
   * Return true if this node is FULLY included resulting in table join. If the
   * node is not included but its parent has been included then a "bean proxy"
   * is added and false is returned.
   * </p>
   */
  private boolean isIncludeBean(String prefix) {

    if (queryDetail.includes(prefix)) {
      // explicitly included
      summary.append(", ").append(prefix);
      String[] splitNames = SplitName.split(prefix);
      queryDetail.includeBeanJoin(splitNames[0], splitNames[1]);
      return true;
    }

    return false;
  }

  /**
   * Takes the select includes and the predicates includes and determines the
   * extra joins required to support the predicates (that are not already
   * supported by the select includes).
   * <p>
   * This returns ONLY the leaves. The joins for the leaves
   * </p>
   */
  private static class IncludesDistiller {

    private final Set<String> selectIncludes;
    private final Set<String> predicateIncludes;

    /**
     * Contains the 'root' extra joins. We only return the roots back.
     */
    private final Map<String, SqlTreeNodeExtraJoin> joinRegister = new HashMap<String, SqlTreeNodeExtraJoin>();

    /**
     * Register of all the extra join nodes.
     */
    private final Map<String, SqlTreeNodeExtraJoin> rootRegister = new HashMap<String, SqlTreeNodeExtraJoin>();

    private final BeanDescriptor<?> desc;

    private IncludesDistiller(BeanDescriptor<?> desc, Set<String> selectIncludes,
                              Set<String> predicateIncludes) {
      this.desc = desc;
      this.selectIncludes = selectIncludes;
      this.predicateIncludes = predicateIncludes;
    }

    /**
     * Build the collection of extra joins returning just the roots.
     * <p>
     * each root returned here could contain a little tree of joins. This
     * follows the more natural pattern and allows for forcing outer joins from
     * a join to a 'many' down through the rest of its tree.
     * </p>
     */
    private Collection<SqlTreeNodeExtraJoin> getExtraJoinRootNodes() {

      String[] extras = findExtras();
      if (extras.length == 0) {
        return rootRegister.values();
      }

      // sort so we process only getting the leaves
      // excluding nodes between root and the leaf
      Arrays.sort(extras);

      // reverse order so get the leaves first...
      for (int i = 0; i < extras.length; i++) {
        createExtraJoin(extras[i]);
      }

      return rootRegister.values();
    }

    private void createExtraJoin(String includeProp) {

      SqlTreeNodeExtraJoin extraJoin = createJoinLeaf(includeProp);
      if (extraJoin != null) {
        // add the extra join...

        // find root of this extra join... linking back to the
        // parents (creating the tree) as it goes.
        SqlTreeNodeExtraJoin root = findExtraJoinRoot(includeProp, extraJoin);

        // register the root because these are the only ones we
        // return back.
        rootRegister.put(root.getName(), root);
      }
    }

    /**
     * Create a SqlTreeNodeExtraJoin, register and return it.
     */
    private SqlTreeNodeExtraJoin createJoinLeaf(String propertyName) {

      ElPropertyValue elGetValue = desc.getElGetValue(propertyName);

      if (elGetValue == null) {
        // this can occur for master detail queries
        // with concatenated keys (so not an error now)
        return null;
      }
      BeanProperty beanProperty = elGetValue.getBeanProperty();
      if (beanProperty instanceof BeanPropertyAssoc<?>) {
        BeanPropertyAssoc<?> assocProp = (BeanPropertyAssoc<?>) beanProperty;
        if (assocProp.isEmbedded()) {
          // no extra join required for embedded beans
          return null;
        }
        SqlTreeNodeExtraJoin extraJoin = new SqlTreeNodeExtraJoin(propertyName, assocProp);
        joinRegister.put(propertyName, extraJoin);
        return extraJoin;
      }
      return null;
    }

    /**
     * Find the root the this extra join tree.
     * <p>
     * This may need to create a parent join implicitly if a predicate join
     * 'skips' a level. e.g. where details.user.id = 1 (maybe join to details is
     * not specified and is implicitly created.
     * </p>
     */
    private SqlTreeNodeExtraJoin findExtraJoinRoot(String includeProp,
                                                   SqlTreeNodeExtraJoin childJoin) {

      int dotPos = includeProp.lastIndexOf('.');
      if (dotPos == -1) {
        // no parent possible(parent is root)
        return childJoin;

      } else {
        // look in register ...
        String parentPropertyName = includeProp.substring(0, dotPos);
        if (selectIncludes.contains(parentPropertyName)) {
          // parent already handled by select
          return childJoin;
        }

        SqlTreeNodeExtraJoin parentJoin = joinRegister.get(parentPropertyName);
        if (parentJoin == null) {
          // we need to create this the parent implicitly...
          parentJoin = createJoinLeaf(parentPropertyName);
        }

        parentJoin.addChild(childJoin);
        return findExtraJoinRoot(parentPropertyName, parentJoin);
      }
    }

    /**
     * Find the extra joins required by predicates and not already taken care of
     * by the select.
     */
    private String[] findExtras() {

      List<String> extras = new ArrayList<String>();

      for (String predProp : predicateIncludes) {
        if (!selectIncludes.contains(predProp)) {
          extras.add(predProp);
        }
      }
      return extras.toArray(new String[extras.size()]);
    }

  }
}
