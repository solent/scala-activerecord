package com.github.aselab.activerecord

import squeryl.Implicits._
import org.squeryl._
import org.squeryl.dsl._
import org.squeryl.PrimitiveTypeMode._
import mojolly.inflector.InflectorImports._
import dsl.keyedEntityDef
import ReflectionUtil._

trait Association[K1, K2, O <: ActiveRecordBase[K1], T <: ActiveRecordBase[K2]] {
  protected var cached = false
  protected var cache: List[T] = Nil

  val owner: O
  val associationClass: Class[T]
  def condition: T => ast.LogicalBoolean

  protected lazy val companion = classToCompanion(associationClass)
    .asInstanceOf[ActiveRecordBaseCompanion[K2, T]]

  def relation: ActiveRecord.Relation[K2, T, T] = {
    ActiveRecord.Relation(companion.table, companion, {m: T => m})
      .where(condition)
  }

  def toList: List[T] = if (cached) cache else reload.toList

  def reload: this.type = {
    inTransaction { cache = relation.toQuery.toList }
    cached = true
    this
  }
}

class BelongsToAssociation[K1, K2, O <: ActiveRecordBase[K1], T <: ActiveRecordBase[K2]](
  val owner: O, val associationClass: Class[T], foreignKey: String
) extends Association[K1, K2, O, T] {

  def this(owner: O, associationClass: Class[T]) = this(owner, associationClass,
    Config.schema.foreignKeyFromClass(associationClass))

  lazy val fieldInfo = owner._companion.fieldInfo(foreignKey)
  
  def condition: T => ast.LogicalBoolean = {
    m => fieldInfo.toEqualityExpression(m.id, owner.getValue(foreignKey))
  }

  def get: Option[T] = toList.headOption

  def assign(m: T): T = {
    cache = List(m)
    cached = true

    val v = if (fieldInfo.isOption) {
      Option(m.id)
    } else {
      m.id
    }
    owner.setValue(foreignKey, v)
    m
  }

  def :=(m: T): T = assign(m)
}

class HasManyAssociation[K1, K2, O <: ActiveRecordBase[K1], T <: ActiveRecordBase[K2]](
  val owner: O, val associationClass: Class[T], foreignKey: String
) extends Association[K1, K2, O, T] {

  def this(owner: O, associationClass: Class[T]) = this(owner, associationClass,
    Config.schema.foreignKeyFromClass(owner.getClass))

  lazy val fieldInfo = companion.fieldInfo(foreignKey)

  def condition: T => ast.LogicalBoolean = {
    m => fieldInfo.toEqualityExpression(m.getValue(foreignKey), owner.id)
  }

  def associate(m: T): T = inTransaction {
    if (cached) reload
    cache :+= m

    val v = if (fieldInfo.isOption) {
      Option(owner.id)
    } else {
      owner.id
    }
    m.setValue(foreignKey, v)
    m.save
    m
  }

  def <<(m: T): T = associate(m)
}

//--------------------------------------------------------------
trait RecordRelation

case class ActiveRecordOneToMany[M <: ActiveRecordBase[_]]
  (override val relation: OneToMany[M])
  extends StatefulOneToMany(relation) with RecordRelation
{
  override def refresh: Unit = dsl.inTransaction { super.refresh }

  private var requireRefresh = false

  def assign(m: M): M = {
    requireRefresh = true
    relation.assign(m)
  }

  override def iterator: Iterator[M] = {
    if (requireRefresh) {
      refresh
      requireRefresh = false
    }
    super.iterator
  }

  override def associate(m: M): M =
    dsl.inTransaction { super.associate(m) }

  override def deleteAll: Int = dsl.inTransaction { super.deleteAll }
}

case class ActiveRecordManyToOne[O <: ActiveRecord]
  (override val relation: ManyToOne[O])
  extends StatefulManyToOne(relation) with RecordRelation
{
  override def refresh: Unit = dsl.inTransaction { super.refresh }

  override def delete: Boolean = dsl.inTransaction { super.delete }
}

case class ActiveRecordManyToMany[O <: ActiveRecord, A <: ActiveRecordBase[_]]
  (override val relation: ManyToMany[O, A])
  extends StatefulManyToMany(relation) with RecordRelation
{
  override def refresh: Unit = dsl.inTransaction { super.refresh }

  private var requireRefresh = false

  def assign(o: O, a: A): A = {
    requireRefresh = true
    relation.assign(o, a)
  }

  def assign(o: O): A = {
    requireRefresh = true
    relation.assign(o)
  }

  override def iterator: Iterator[O] = {
    if (requireRefresh) {
      refresh
      requireRefresh = false
    }
    super.iterator
  }

  override def associate(o: O, a: A): A =
    dsl.inTransaction { super.associate(o, a) }

  override def associate(o: O): A = dsl.inTransaction { super.associate(o) }

  override def dissociate(o: O): Boolean = dsl.inTransaction { super.dissociate(o) }

  override def dissociateAll: Int = dsl.inTransaction { super.dissociateAll }
}

case class RelationWrapper[L <: ActiveRecord, R <: ActiveRecordBase[_]](relation: Relation[L, R]) {
  type M2M = ActiveRecordManyToMany[ActiveRecord, ActiveRecordBase[_]]

  private def oneToManyRelation = relation.asInstanceOf[OneToManyRelation[L, R]]
  private def manyToManyRelation =
    relation.asInstanceOf[ManyToManyRelation[ActiveRecord, ActiveRecord, ActiveRecordBase[_]]]

  def belongsTo(m: R): ActiveRecordManyToOne[L] =
    ActiveRecordManyToOne(oneToManyRelation.right(m))

  def hasMany(m: L): ActiveRecordOneToMany[R] =
    ActiveRecordOneToMany(oneToManyRelation.left(m))

  def hasAndBelongsToManyL(m: L): M2M =
    ActiveRecordManyToMany(manyToManyRelation.left(m))

  def hasAndBelongsToManyR(m: R)(implicit ev: R <:< ActiveRecord): M2M =
    ActiveRecordManyToMany(manyToManyRelation.right(m))
}

trait ActiveRecordBaseRelationSupport {self: ActiveRecordBase[_] =>

  protected def relations: Map[(String, String), RelationWrapper[ActiveRecord, ActiveRecordBase[_]]]

  protected def getRelation(left: Class[_], right: Class[_]) =
    relations.get(left.getName -> right.getName)
     .getOrElse(throw ActiveRecordException.missingRelation)

  protected def belongsTo[T <: ActiveRecord](implicit m: Manifest[T]) =
    getRelation(m.erasure, getClass).belongsTo(self).asInstanceOf[ActiveRecordManyToOne[T]]

}

trait ActiveRecordRelationSupport extends ActiveRecordBaseRelationSupport {
  self: ActiveRecord =>

  protected def hasMany[T <: ActiveRecordBase[_]](implicit m: Manifest[T]) =
    getRelation(getClass, m.erasure).hasMany(self).asInstanceOf[ActiveRecordOneToMany[T]]

  protected def hasManyThrough[A <: ActiveRecord, B <: IntermediateRecord]
  (implicit m: Manifest[A]) =
    getSymmetricRelation(getClass, m.erasure)
      .asInstanceOf[ActiveRecordManyToMany[A, B]]

  protected def hasAndBelongsToMany[T <: ActiveRecord](implicit m: Manifest[T])=
    getSymmetricRelation(getClass, m.erasure)
      .asInstanceOf[ActiveRecordManyToMany[T, DefaultIntermediateRecord]]

  private def getSymmetricRelation(c1: Class[_], c2: Class[_]) =
    relations.get(c1.getName -> c2.getName)
      .map(_.hasAndBelongsToManyL(self)).getOrElse(getRelation(c2, c1)
      .asInstanceOf[RelationWrapper[ActiveRecord, ActiveRecord]]
      .hasAndBelongsToManyR(self))
}

trait TableRelationSupport extends Schema {
  type AR = com.github.aselab.activerecord.ActiveRecord

  lazy val relations = {
    this.getFields[Relation[AR, ActiveRecordBase[_]]].map {f =>
      val List(left, right, _*) = getGenericTypes(f).map(_.getName)
      val relation = this.getValue[Relation[AR, ActiveRecordBase[_]]](f.getName)

      (left, right) -> RelationWrapper[AR, ActiveRecordBase[_]](relation)
    }.toMap
  }

  def foreignKeyName(c: Class[_]): String = c.getSimpleName.underscore.camelize + "Id"

  def foreignKeyIsOption(c: Class[_], name: String): Boolean = try {
    c.getDeclaredField(name).getType.getName == "scala.Option"
  } catch {
    case e: java.lang.NoSuchFieldException =>
      throw ActiveRecordException.missingForeignKey(name)
  }

  def oneToMany[O <: AR, M <: ActiveRecordBase[_]]
    (ot: Table[O], mt:Table[M])(implicit om: Manifest[O], mm: Manifest[M]): Relation[O, M] =
  {

    val foreignKey = foreignKeyName(om.erasure)
    val isOption= foreignKeyIsOption(mm.erasure, foreignKey)

    val relation = oneToManyRelation(ot, mt).via {(o, m) =>
      if (isOption) {
        o.id === m.getValue[Option[Long]](foreignKey)
      } else {
        o.id === m.getValue[Long](foreignKey)
      }
    }

    if (isOption) {
      relation.foreignKeyDeclaration.constrainReference(onDelete setNull)
    } else {
      relation.foreignKeyDeclaration.constrainReference(onDelete cascade)
    }

    relation
  }

  def manyToMany[L <: AR, M <: IntermediateRecord, R <: AR]
    (lt: Table[L], mt: Table[M], rt:Table[R])
    (implicit lm:Manifest[L], mm:Manifest[M], rm:Manifest[R]): Relation[L, R] =
  {
    val foreignKeyL = foreignKeyName(lm.erasure)
    val foreignKeyR = foreignKeyName(rm.erasure)

    val relation = manyToManyRelation(lt, rt, mt.name).via[M] {(l, r, m) =>
      (l.id === m.getValue[Long](foreignKeyL),
       r.id === m.getValue[Long](foreignKeyR))
    }

    relation.leftForeignKeyDeclaration.constrainReference(onDelete cascade)
    relation.rightForeignKeyDeclaration.constrainReference(onDelete cascade)

    relation
  }

  def manyToMany[L <: AR, R <: AR](lt: Table[L], rt:Table[R])
    (implicit lm: Manifest[L], rm: Manifest[R]): Relation[L, R] =
  {
    val middleName =
      tableNameFromClass(lm.erasure) + "_" + tableNameFromClass(rm.erasure)

    implicit val ked = DefaultIntermediateRecord.keyedEntityDef
    val relation = manyToManyRelation(lt, rt, middleName)
      .via[DefaultIntermediateRecord](
        (l, r, m) => (l.id === m.leftId, r.id === m.rightId)
      )

    relation.leftForeignKeyDeclaration.constrainReference(onDelete cascade)
    relation.rightForeignKeyDeclaration.constrainReference(onDelete cascade)

    relation
  }
}

/**
 * Base class of intermediate table for many to many relationship.
 */
abstract class IntermediateRecord extends ActiveRecordBase[CompositeKey2[Long, Long]] with KeyedEntity[CompositeKey2[Long, Long]]

/**
 * Base class of IntermediateRecord companion objects.
 */
trait IntermediateRecordCompanion[T <: IntermediateRecord]
  extends ActiveRecordBaseCompanion[CompositeKey2[Long, Long], T]

case class DefaultIntermediateRecord() extends IntermediateRecord {
  val leftId: Long = 0
  val rightId: Long = 0
  def id: CompositeKey2[Long, Long] = compositeKey(leftId, rightId)
}

object DefaultIntermediateRecord {
  type K = CompositeKey2[Long, Long]
  val keyedEntityDef = new KeyedEntityDef[DefaultIntermediateRecord, K] {
    def getId(m: DefaultIntermediateRecord) = m.id
    def isPersisted(m: DefaultIntermediateRecord) = m.isPersisted
    def idPropertyName = "id"
  }
}

class IntermediateTable[T <: ActiveRecordBase[_]]
  (name: String, schema: Schema)(implicit m: Manifest[T])
  extends DummyTable[T](name, schema)(m, keyedEntityDef(m))
