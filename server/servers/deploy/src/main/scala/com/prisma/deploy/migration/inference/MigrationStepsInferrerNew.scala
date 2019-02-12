package com.prisma.deploy.migration.inference

import com.prisma.deploy.connector.DatabaseSchema
import com.prisma.deploy.schema.UpdatedRelationAmbiguous
import com.prisma.shared.models._

//trait MigrationStepsInferrer {
//  def infer(previousSchema: Schema, nextSchema: Schema, renames: SchemaMapping): Vector[MigrationStep]
//}
//

case class MigrationStepsInferrerImplNew(previousSchema: Schema, nextSchema: Schema, databaseSchema: DatabaseSchema, renames: SchemaMapping) {
  import com.prisma.util.Diff._

  /**
    * The following evaluation order considers all interdependencies:
    * - Delete Relation
    * - Delete Field
    * - Delete Model
    * - Delete Enum
    * - Create Enum
    * - Create Model
    * - Create Field
    * - Create Relation
    * - Update Enum
    * - Update Field
    * - Update Model
    * - Update Relation
    *
    * Note that all actions can be performed on the database level without the knowledge of previous or next migration steps.
    * This would not be true if, for example, the order would be reversed, as field updates and deletes would need to know the new
    * table name instead of the old one to successfully execute their SQL statements, increasing implementation complexity
    * and having more surface area for bugs. The model shown allows to _just look at the previous project_ and apply
    * all steps, instead of knowing the next project state as well.
    */
  def evaluate(): Vector[MigrationStep] = {
    relationsToDelete ++
      fieldsToDelete ++
      modelsToDelete ++
      enumsToDelete ++
      enumsToUpdate ++
      fieldsToUpdate ++
      modelsToUpdateFirstStep ++
      modelsToUpdateSecondStep ++
      relationsToUpdate ++
      enumsToCreate ++
      modelsToCreate ++
      fieldsToCreate ++
      relationsToCreate
  }

//  lazy val modelsToCreate: Vector[CreateModel] = {
//    for {
//      nextModel <- nextSchema.models.toVector
////      previousModelName = renames.getPreviousModelName(nextModel.name)
////      if previousSchema.getModelByName(previousModelName).isEmpty
//      if databaseSchema.table(nextModel.dbName).isEmpty
//      if !renames.wasModelRenamed(nextModel.name) // todo: is that needed in this new approach?
//    } yield CreateModel(nextModel.name)
//  }
//
//  lazy val modelsToUpdate: Vector[UpdateModel] = {
//    for {
//      nextModel         <- nextSchema.models.toVector
//      previousModelName = renames.getPreviousModelName(nextModel.name)
////      previousModel     <- previousSchema.getModelByName(previousModelName)
////      if nextModel.name != previousModel.name || nextModel.isEmbedded != previousModel.isEmbedded
//      if databaseSchema.table(nextModel.dbName).isEmpty
//      if renames.wasModelRenamed(nextModel.name)
//    } yield UpdateModel(name = previousModelName, newName = nextModel.name)
//  }

  lazy val modelSteps = {
    for {
      nextModel         <- nextSchema.models.toVector
      previousModelName = renames.getPreviousModelName(nextModel.name)
      if databaseSchema.table(nextModel.dbName).isEmpty
    } yield {
      if (renames.wasModelRenamed(nextModel.name)) {
        UpdateModel(name = previousModelName, newName = nextModel.name)
      } else {
        CreateModel(nextModel.name)
      }
    }
  }

  lazy val modelsToCreate = modelSteps.collect { case x: CreateModel => x }
  lazy val modelsToUpdate = modelSteps.collect { case x: UpdateModel => x }

  lazy val modelsToUpdateFirstStep: Vector[UpdateModel]  = modelsToUpdate.map(update => update.copy(newName = "__" + update.newName))
  lazy val modelsToUpdateSecondStep: Vector[UpdateModel] = modelsToUpdate.map(update => update.copy(name = "__" + update.newName))

  /*
   * Check all previous models if they are present on on the new one, ignore renames (== updated models).
   * Use the _previous_ model name to check presence, and as updates are ignored this has to yield a result,
   * or else the model is deleted.
   */
  lazy val modelsToDelete: Vector[DeleteModel] = {
    val updatedModels = modelsToUpdate.map(_.name)
    for {
      previousModel <- previousSchema.models.toVector.filterNot(m => updatedModels.contains(m.name))
      if nextSchema.getModelByName(previousModel.name).isEmpty
      if databaseSchema.table(previousModel.dbName).isDefined
    } yield DeleteModel(previousModel.name)
  }

  lazy val fieldsToCreate: Vector[FieldMigrationStep] = {
//    for {
//      nextModel         <- nextSchema.models.toVector
//      previousModelName = renames.getPreviousModelName(nextModel.name)
//      previousModel     = previousSchema.getModelByName(previousModelName).getOrElse(Model.empty)
//      fieldOfNextModel  <- nextModel.fields.toVector
//      previousFieldName = renames.getPreviousFieldName(nextModel.name, fieldOfNextModel.name)
//      if previousModel.getFieldByName(previousFieldName).isEmpty
//      if !fieldOfNextModel.isMagicalBackRelation
//    } yield {
//      CreateField(model = nextModel.name, name = fieldOfNextModel.name)
//    }
    val x = for {
      nextModel <- nextSchema.models.toVector
      nextField <- nextModel.scalarFields.toVector
      column    = databaseSchema.table(nextModel.dbName).flatMap(_.column(nextField.dbName))
    } yield {
      column match {
        case None =>
          Some(CreateField(model = nextModel.name, name = nextField.name))
        case Some(c) =>
          if (nextField.dbName != c.name || nextField.typeIdentifier != c.typeIdentifier || nextField.isUnique != c.isUnique || nextField.isRequired != c.isRequired) {
            Some(
              UpdateField(
                model = nextModel.name,
                newModel = nextModel.name,
                name = nextField.name,
                newName = None
              ))
          } else {
            None
          }
      }
    }
    x.flatten
  }

  lazy val fieldsToUpdate: Vector[FieldMigrationStep] = {
    val updates = for {
      nextModel         <- nextSchema.models.toVector
      previousModelName = renames.getPreviousModelName(nextModel.name)
      previousModel     = previousSchema.getModelByName(previousModelName).getOrElse(Model.empty)
      nextField         <- nextModel.fields.toVector
      previousFieldName = renames.getPreviousFieldName(nextModel.name, nextField.name)
      previousField     <- previousModel.getFieldByName(previousFieldName)
      if didSomethingChange(previousField.template, nextField.template)(_.name,
                                                                        _.typeIdentifier,
                                                                        _.isUnique,
                                                                        _.isRequired,
                                                                        _.isList,
                                                                        _.manifestation,
                                                                        _.behaviour)

      column = databaseSchema.table(nextModel.dbName).flatMap(_.column(nextField.dbName))
    } yield {

      column match {
        case None =>
          Some(CreateField(model = nextModel.name, name = nextField.name))
        case Some(c) =>
          if (nextField.dbName != c.name || nextField.typeIdentifier != c.typeIdentifier || nextField.isUnique != c.isUnique || nextField.isRequired != c.isRequired) {
            Some(
              UpdateField(
                model = previousModelName,
                newModel = nextModel.name,
                name = previousFieldName,
                newName = diff(previousField.name, nextField.name)
              ))
          } else {
            None
          }

      }
    }

    updates.flatten
  }

//  lazy val fieldSteps = {
//    for {
//      nextModel        <- nextSchema.models.toVector
//      fieldOfNextModel <- nextModel.fields.toVector
//      column           = databaseSchema.table(nextModel.dbName).flatMap(_.column(fieldOfNextModel.dbName))
//    } yield {
//      if(column.isEmpty){
//        CreateField(model = nextModel.name, name = fieldOfNextModel.name)
//      } else {}
//    }
//  }

  lazy val fieldsToDelete: Vector[DeleteField] = {
    for {
      previousModel <- previousSchema.models.toVector
      previousField <- previousModel.fields.filterNot(_.isMagicalBackRelation)
      nextModelName = renames.getNextModelName(previousModel.name)
      nextFieldName = renames.getNextFieldName(previousModel.name, previousField.name)
      nextModel     <- nextSchema.getModelByName(nextModelName)
      if nextModel.getFieldByName(nextFieldName).isEmpty
    } yield DeleteField(model = previousModel.name, name = previousField.name)
  }

  lazy val relationsToCreate: Vector[CreateRelation] = {
    for {
      nextRelation <- nextSchema.relations.toVector
      if relationNotInPreviousSchema(previousSchema, nextSchema = nextSchema, nextRelation, renames.getPreviousModelName, renames.getPreviousRelationName)
    } yield {
      CreateRelation(name = nextRelation.name)
    }
  }

  lazy val relationsToDelete: Vector[DeleteRelation] = {
    for {
      previousRelation <- previousSchema.relations.toVector
      if relationNotInNextSchema(nextSchema, previousSchema = previousSchema, previousRelation, renames.getNextModelName, renames.getNextRelationName)
    } yield DeleteRelation(previousRelation.name)
  }

  lazy val relationsToUpdate: Vector[UpdateRelation] = {
    val updates = for {
      previousRelation <- previousSchema.relations.toVector
      nextModelAName   = renames.getNextModelName(previousRelation.modelAName)
      nextModelBName   = renames.getNextModelName(previousRelation.modelBName)
      nextRelation <- nextSchema
                       .getRelationByName(renames.getNextRelationName(previousRelation.name))
                       .orElse {
                         val previousWasAmbiguous = previousSchema
                           .getRelationsThatConnectModels(previousRelation.modelAName, previousRelation.modelBName)
                           .size > 1
                         val nextIsAmbiguous = nextSchema.getRelationsThatConnectModels(nextModelAName, nextModelBName).size > 1

                         (previousWasAmbiguous, nextIsAmbiguous) match {
                           case (true, true)   => None
                           case (true, false)  => None
                           case (false, true)  => None
                           case (false, false) => nextSchema.getRelationsThatConnectModels(nextModelAName, nextModelBName).headOption
                         }
                       }
      if didSomethingChange(previousRelation, nextRelation)(_.name, _.modelAName, _.modelBName, _.manifestation)
    } yield {
      UpdateRelation(name = previousRelation.name, newName = diff(previousRelation.name, nextRelation.name))
    }
    def isContainedInDeletes(update: UpdateRelation) = relationsToDelete.map(_.name).contains(update.name)

    updates.filterNot(isContainedInDeletes)
  }

  lazy val enumsToCreate: Vector[CreateEnum] = {
    for {
      nextEnum         <- nextSchema.enums.toVector
      previousEnumName = renames.getPreviousEnumName(nextEnum.name)
      if !containsEnum(previousSchema, previousEnumName)
    } yield CreateEnum(nextEnum.name)
  }

  lazy val enumsToDelete: Vector[DeleteEnum] = {
    for {
      previousEnum <- previousSchema.enums.toVector
      nextEnumName = renames.getNextEnumName(previousEnum.name)
      if nextSchema.getEnumByName(nextEnumName).isEmpty
    } yield DeleteEnum(previousEnum.name)
  }

  lazy val enumsToUpdate: Vector[UpdateEnum] = {
    val updates = for {
      previousEnum <- previousSchema.enums.toVector
      nextEnumName = renames.getNextEnumName(previousEnum.name)
      nextEnum     <- nextSchema.getEnumByName(nextEnumName)
      if previousEnum != nextEnum
    } yield {
      UpdateEnum(
        name = previousEnum.name,
        newName = diff(previousEnum.name, nextEnum.name)
      )
    }
    updates
  }

  def relationNotInPreviousSchema(previousSchema: Schema,
                                  nextSchema: Schema,
                                  nextRelation: Relation,
                                  previousModelName: String => String,
                                  previousRelationName: String => String): Boolean = {

    val nextGeneratedRelationName      = generateRelationName(nextRelation.modelAName, nextRelation.modelBName)
    val nextRelationCountBetweenModels = nextSchema.relations.count(relation => relation.connectsTheModels(nextRelation.modelAName, nextRelation.modelBName))

    val relationInPreviousSchema = previousSchema.relations.exists { previousRelation =>
      val previousModelAId              = previousModelName(nextRelation.modelAName)
      val previousModelBId              = previousModelName(nextRelation.modelBName)
      val previousGeneratedRelationName = generateRelationName(previousModelAId, previousModelBId)

      val refersToModelsExactlyRight = previousRelation.modelAName == previousModelAId && previousRelation.modelBName == previousModelBId
      val refersToModelsSwitched     = previousRelation.modelAName == previousModelBId && previousRelation.modelBName == previousModelAId

      val nameIsUnchanged = previousRelation.name == nextRelation.name && !renames.relations.exists(m => m.next == nextRelation.name && m.previous != m.next)

      val relationNameMatches = previousRelation.name == previousGeneratedRelationName || nameIsUnchanged || previousRelation.name == previousRelationName(
        nextRelation.name)

      val previousRelationCountBetweenModels = previousSchema.relations.count(relation => relation.connectsTheModels(previousModelAId, previousModelBId))

      if (nextRelation.name == nextGeneratedRelationName && nextRelationCountBetweenModels == 1 && previousRelationCountBetweenModels > 1)
        throw UpdatedRelationAmbiguous(
          s"There is a relation ambiguity during the migration. The ambiguity is on a relation between ${previousRelation.modelAName} and ${previousRelation.modelBName}. Please name relations or change the schema in steps.")

      val isNameRemovalOfPreviouslyNamedRelation = nextRelation.name == nextGeneratedRelationName && nextRelationCountBetweenModels == 1 && previousRelationCountBetweenModels == 1
      (relationNameMatches || isNameRemovalOfPreviouslyNamedRelation) && (refersToModelsExactlyRight || refersToModelsSwitched)
    }
    !relationInPreviousSchema
  }

  def relationNotInNextSchema(nextSchema: Schema,
                              previousSchema: Schema,
                              previousRelation: Relation,
                              nextModelName: String => String,
                              nextRelationName: String => String): Boolean = {
    val previousGeneratedRelationName      = generateRelationName(previousRelation.modelAName, previousRelation.modelBName)
    val previousRelationCountBetweenModels = previousSchema.relations.count(_.connectsTheModels(previousRelation.modelAName, previousRelation.modelBName))

    val relationInNextSchema = nextSchema.relations.exists { nextRelation =>
      val nextModelAId              = nextModelName(previousRelation.modelAName)
      val nextModelBId              = nextModelName(previousRelation.modelBName)
      val nextGeneratedRelationName = generateRelationName(nextModelAId, nextModelBId)

      val refersToModelsExactlyRight = nextRelation.modelAName == nextModelAId && nextRelation.modelBName == nextModelBId
      val refersToModelsSwitched     = nextRelation.modelAName == nextModelBId && nextRelation.modelBName == nextModelAId

      val nameIsUnchanged = nextRelation.name == previousRelation.name && !renames.relations.exists(m => m.next == nextRelation.name && m.previous != m.next)

      val nameIsChangedToNextName = nextRelation.name == nextRelationName(previousRelation.name) && renames.relations.exists(p =>
        p.previous == previousRelation.name && p.next != previousRelation.name)

      val relationNameMatches = nextRelation.name == nextGeneratedRelationName || nameIsUnchanged || nameIsChangedToNextName

      val nextRelationCountBetweenModels = nextSchema.relations.count(relation => relation.connectsTheModels(nextModelAId, nextModelBId))

      if (previousRelation.name == previousGeneratedRelationName && nextRelationCountBetweenModels > 1 && previousRelationCountBetweenModels == 1)
        throw UpdatedRelationAmbiguous(
          s"There is a relation ambiguity during the migration. Please first name the old relation on your schema. The ambiguity is on a relation between ${previousRelation.modelAName} and ${previousRelation.modelBName}. Please name relations or change the schema in steps.")

      val isRenameOfPreviouslyUnnamedRelation = previousRelation.name == previousGeneratedRelationName && nextRelationCountBetweenModels == 1 && previousRelationCountBetweenModels == 1

      (relationNameMatches || isRenameOfPreviouslyUnnamedRelation) && (refersToModelsExactlyRight || refersToModelsSwitched)
    }

    !relationInNextSchema
  }

  def generateRelationName(first: String, second: String): String = if (first < second) s"${first}To${second}" else s"${second}To${first}"

  def containsEnum(schema: Schema, enumName: String): Boolean = schema.enums.exists(_.name == enumName)

  def didSomethingChange[T](previous: T, next: T)(fns: (T => Any)*): Boolean = {
    fns.exists { fn =>
      val previousValue = fn(previous)
      val nextValue     = fn(next)
      previousValue != nextValue
    }
  }
}
