package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.ar.*
import com.framer.sense.feature.camera.vlm.avatar.PhotoActionResolver
import com.framer.sense.feature.camera.vlm.model.*
import org.junit.Assert.*
import org.junit.Test

class ArScaleSolverTest {
    private fun camera(height: Float = 1.2f): ArScaleSnapshot {
        val view = SpatialMatrices.identity().toMutableList().apply { this[13] = -height }
        val n = .1f; val f = 40f
        val projection = listOf(2f,0f,0f,0f, 0f,1.5f,0f,0f, 0f,0f,-(f+n)/(f-n),-1f, 0f,0f,-2*f*n/(f-n),0f)
        val base=ArScaleSnapshot("world",1,10,view,projection,SpatialPoint(0f,height,0f),480,640)
        val floor=(0..6).flatMap { i -> listOf(-.2f,0f,.2f).map { x -> SpatialPoint(x,0f,-2.4f-i*.25f) } }
        val background=listOf(SpatialPoint(-.5f,1f,-3.5f),SpatialPoint(.5f,1.2f,-3.5f),SpatialPoint(.8f,1.4f,-3.5f))
        return base.copy(depth=(floor+background).map { ArDepthPoint(SpatialMatrices.project(base,it,CropRect())!!,it,240) })
    }
    private fun plan(shot: ShotType = ShotType.FULL) = PhotoActionResolver().resolve(
        CompositionPlan("p","方案",shot,CropRect(),1f,AvatarPlacement(height=.65f,foot=Point2(.5f,.92f)),"站立","环境",
            viewBinding=PhotoViewBinding("scan","frame",1,"world"),subjectHeightMeters=1.7f,arCamera=camera()))
    private fun metadata() = AvatarScaleMetadata("vlm_avatars/adult_female.glb",1,1,
        SpatialPoint(0f,0f,0f),SpatialPoint(0f,1.6f,0f))
    private fun plane() = ArGroundPlane("floor",listOf(SpatialPoint(-4f,0f,-.25f),SpatialPoint(4f,0f,-.25f),
        SpatialPoint(4f,0f,-12f),SpatialPoint(-4f,0f,-12f)),stableObservations=3)
    private fun span(height: Float,z: Float, camera: ArScaleSnapshot = camera()): Float {
        val foot = SpatialMatrices.project(camera,SpatialPoint(0f,0f,z),CropRect())!!
        val head = SpatialMatrices.project(camera,SpatialPoint(0f,height,z),CropRect())!!
        return foot.y-head.y
    }
    @Test fun personAndDoorAtSameDepthUseRealHeightRatio() {
        assertEquals(.85f,span(1.7f,-4f)/span(2f,-4f),.0001f)
    }
    @Test fun twiceDepthHalvesProjectionAndTwiceFocalDoublesIt() {
        assertEquals(.5f,span(1.7f,-8f)/span(1.7f,-4f),.0001f)
        val c = camera(); val doubled = c.projection.toMutableList().apply { this[0] *= 2; this[5] *= 2 }
        assertEquals(2f,span(1.7f,-4f,c.copy(projection=doubled))/span(1.7f,-4f,c),.0001f)
    }
    @Test fun cropAndRotatedImageProjectionAgreeWithNormalizedCoordinates() {
        val c=camera(); val point=SpatialPoint(.2f,.8f,-3f); val crop=CropRect(.2f,.2f,.8f,.8f)
        val original=SpatialMatrices.project(c,point,CropRect())!!
        val clipped=SpatialMatrices.project(c,point,crop)!!
        val expected=PlanCoordinates.toPlan(original,crop)
        assertEquals(expected.x,clipped.x,.0001f); assertEquals(expected.y,clipped.y,.0001f)
        val projection=SpatialMatrices.imageProjection(c.projection,listOf(Point2(1f,0f),Point2(1f,1f),Point2(0f,0f)))
        val rotated=SpatialMatrices.project(c.copy(projection=projection),point,CropRect())!!
        assertEquals(1-original.y,rotated.x,.0001f); assertEquals(original.x,rotated.y,.0001f)
    }
    @Test fun floorIntersectionRejectsHorizonAndOutsidePolygon() {
        assertNotNull(ArScaleSolver.rayGround(camera(),Point2(.5f,.9f),plane(),CropRect()))
        assertNull(ArScaleSolver.rayGround(camera(),Point2(.5f,.5f),plane(),CropRect()))
        assertFalse(ArScaleSolver.contains(plane(),SpatialPoint(10f,0f,-3f)))
        assertNull(ArScaleSolver.at(plan(),camera(),plane(),SpatialPoint(10f,0f,-3f),metadata()))
    }
    @Test fun solverKeepsRealHeightAndRejectsOldSessionOrMissingFloor() {
        val p=plan(); val g=ArGroundSnapshot("world",1,listOf(plane()))
        val found=ArScaleSolver.solve(p,camera(),g,metadata())!!
        assertEquals(1.7f,found.heightMeters,0f)
        assertTrue(ArScaleSolver.contains(plane(),found.position))
        assertNull(ArScaleSolver.solve(p,camera(),g.copy(sessionId="new"),metadata()))
        assertNull(ArScaleSolver.solve(p,camera(),g.copy(configVersion=2),metadata()))
        assertNull(ArScaleSolver.solve(p,camera(),g.copy(planes=emptyList()),metadata()))
        assertNull(ArScaleSolver.solve(p,camera(),g,metadata().copy(assetVersion=2)))
    }
    @Test fun halfAndCloseUpCanSolveWithFeetOutsideThePicture() {
        for ((shot,height,foot) in listOf(Triple(ShotType.HALF,1.1f,1.42f),Triple(ShotType.CLOSE_UP,2.1f,2.42f))) {
            val p=plan(shot).withActualAvatar(AvatarPlacement(height=height,foot=Point2(.5f,foot)))
            val value=ArScaleSolver.solve(p,if (shot == ShotType.CLOSE_UP) camera(1.55f) else camera(),ArGroundSnapshot("world",1,listOf(plane())),metadata())!!
            assertTrue(value.projectedFoot.y > 1f)
            assertEquals(1.7f,value.heightMeters,0f)
        }
    }
    @Test fun finalSpecPreservesPhysicalScaleAndDoesNotEnterV1Json() {
        val p=plan(); val place=ArScaleSolver.solve(p,camera(),ArGroundSnapshot("world",1,listOf(plane())),metadata())!!
        val calibrated=ArFraming.apply(p,place).withRevision(1)
        assertTrue(calibrated.resolvedSpec!!.matches(calibrated))
        val smile=calibrated.withActualAvatar(calibrated.avatar.copy(expression=ExpressionId.SMILE)).withRevision(2)
        assertEquals(place,smile.spatialPlacement)
        assertEquals(place,smile.resolvedSpec!!.spatialPlacement)
        val json=VlmJson.encodeToString(CompositionPlan.serializer(),smile)
        assertFalse(json.contains("spatialPlacement")); assertFalse(json.contains("subjectHeightMeters")); assertFalse(json.contains("arCamera"))
    }
    @Test fun legacyPreferencesUseDefaultsAndEachRoleHasOwnHeight() {
        val old=VlmJson.decodeFromString<PhotoPreferences>("{}")
        assertEquals(165,old.heightCm(AvatarId.ADULT_FEMALE)); assertEquals(175,old.heightCm(AvatarId.ADULT_MALE))
        assertEquals(120,old.heightCm(AvatarId.CHILD_BOY))
        val modified=old.copy(heightsCm=mapOf(AvatarId.ADULT_FEMALE to 170,AvatarId.CHILD_GIRL to 999))
        assertEquals(170,modified.heightCm(AvatarId.ADULT_FEMALE)); assertEquals(180,modified.heightCm(AvatarId.CHILD_GIRL))
    }
    @Test fun fixedTemplateHeightDoesNotControlArStandingPositionOrFinalScale() {
        val first=plan().copy(arDesiredAvatar=AvatarPlacement(height=.08f,foot=Point2(.5f,.2f)))
        val second=plan().copy(arDesiredAvatar=AvatarPlacement(height=3.8f,foot=Point2(.5f,3f)))
        val ground=ArGroundSnapshot("world",1,listOf(plane()))
        val a=ArScaleSolver.solve(first,camera(),ground,metadata())!!
        val b=ArScaleSolver.solve(second,camera(),ground,metadata())!!
        assertEquals(a.position,b.position)
        assertEquals(a.projectedHeight,b.projectedHeight,0f)
        assertEquals(a.framingCrop,b.framingCrop)
    }
    @Test fun changingShotCropsBothEnvironmentAndPersonWithoutChangingTheirRatio() {
        val ground=ArGroundSnapshot("world",1,listOf(plane()))
        ShotType.entries.forEach { shot ->
            val p=plan(shot)
            val solved=ArScaleSolver.solve(p,camera(),ground,metadata())!!
            val actual=ArFraming.apply(p,solved)
            val point=solved.position
            val foot=SpatialMatrices.project(camera(),point,actual.crop)!!
            val person=SpatialMatrices.project(camera(),point.copy(y=point.y+1.7f),actual.crop)!!
            val door=SpatialMatrices.project(camera(),point.copy(y=point.y+2f),actual.crop)!!
            assertEquals(.85f,(foot.y-person.y)/(foot.y-door.y),.0001f)
            assertEquals(solved.framingCrop,actual.crop)
            assertTrue(actual.resolvedSpec!!.matches(actual))
            assertEquals(1f,actual.zoom,0f)
        }
    }
    @Test fun digitalCropPreservesProtectedBackgroundOrReportsImpossibleFraming() {
        val p=plan(ShotType.HALF).copy(backgroundKeepRegions=listOf(CropRect()))
        val placement=ArScaleSolver.solve(p,camera(),ArGroundSnapshot("world",1,listOf(plane())),metadata())
        if (placement != null) assertTrue(ArFraming.keepsBackground(placement.framingCrop,p.backgroundKeepRegions))
        assertNull(ArFraming.crop(ShotType.CLOSE_UP,Point2(.5f,.88f),.2f,listOf(CropRect())))
    }
    @Test fun cropCompositionAndTapMappingUseTheSameCameraRegion() {
        val viewport=CropRect(.1f,0f,.9f,1f); val frame=CropRect(.25f,.25f,.75f,.75f)
        val combined=ArFraming.combine(viewport,frame)
        assertEquals(.3f,combined.left,.0001f); assertEquals(.7f,combined.right,.0001f)
        val tap=PlanCoordinates.toOriginal(Point2(.5f,.5f),frame)
        assertEquals(Point2(.5f,.5f),tap)
    }
    @Test fun automaticPlacementPrefersFloorInsteadOfTabletop() {
        val table=plane().copy(id="table",boundary=plane().boundary.map { it.copy(y=.65f) })
        val result=ArScaleSolver.solve(plan(),camera(),ArGroundSnapshot("world",1,listOf(table,plane())),metadata())!!
        assertEquals("floor",result.groundId)
        assertEquals(0f,result.position.y,0f)
    }

    @Test fun trackedFloorWorksWithoutDepthAndPreservesRealHeight() {
        val c=camera().copy(depth=emptyList())
        val p=plan().copy(arCamera=c)
        val automatic=ArScaleSolver.solve(p,c,ArGroundSnapshot("world",1,listOf(plane())),metadata())!!
        assertEquals(1.7f,automatic.heightMeters,0f)
        assertFalse(automatic.manual)
        val confirmed=ArScaleSolver.at(p,c,plane(),SpatialPoint(0f,0f,-3f),metadata(),manual=true)!!
        assertEquals(1.7f,confirmed.heightMeters,0f)
        assertTrue(confirmed.manual)
    }
    @Test fun unstablePlaneAndTableFailButSparseDepthDoesNotBlockTrackedFloor() {
        val c=camera(); val p=plan()
        assertNull(ArScaleSolver.solve(p,c,ArGroundSnapshot("world",1,listOf(plane().copy(stableObservations=2))),metadata()))
        val weak=c.copy(depth=c.depth.map { it.copy(confidence=10) })
        assertNotNull(ArScaleSolver.solve(p,weak,ArGroundSnapshot("world",1,listOf(plane())),metadata()))
        val table=plane().copy(boundary=plane().boundary.map { it.copy(y=.65f) })
        assertFalse(ArSceneConstraints.reliableFloor(c,table))
    }
    @Test fun foregroundObstructionAndNearCameraGuessAreRejected() {
        val c=camera()
        assertNull(ArScaleSolver.at(plan(),c,plane(),SpatialPoint(0f,0f,-1f),metadata()))
        val obstruction=(0..2).map { ArDepthPoint(Point2(.49f+it*.01f,.55f),SpatialPoint(0f,.8f,-1.5f),255) }
        assertNull(ArScaleSolver.at(plan(),c.copy(depth=c.depth+obstruction),plane(),SpatialPoint(0f,0f,-3f),metadata()))
    }
    @Test fun losingMeasurementNeverRestoresTemplateHeight() {
        val failed=plan().withScaleFailure(ScaleFailure.DEPTH_MISSING)
        assertEquals(0f,failed.avatar.height,0f)
        assertNull(failed.spatialPlacement)
        assertFalse(failed.hasMeasuredScale())
        assertTrue(failed.resolvedSpec!!.matches(failed))
        assertEquals(ScaleFailure.DEPTH_MISSING,failed.scaleFailure)
    }

    @Test fun halfShotDoesNotRequireFeetInsideTheOriginalPhoto() {
        val c=camera().copy(depth=emptyList())
        val value=ArScaleSolver.at(plan(ShotType.HALF),c,plane(),SpatialPoint(0f,0f,-1.5f),metadata())!!
        assertTrue(SpatialMatrices.project(c,value.position,CropRect())!!.y > 1f)
        assertEquals(1.7f,value.heightMeters,0f)
    }
    @Test fun solverReportsActualRejectionCategoriesOnce() {
        var reports=0; var result: ArSolveDiagnostics?=null
        assertNull(ArScaleSolver.solve(plan(),camera(),ArGroundSnapshot("world",1,listOf(plane().copy(stableObservations=1))),
            metadata(),onDiagnostics={ reports++; result=it }))
        assertEquals(1,reports)
        assertEquals(0,result!!.eligiblePlanes)
        assertTrue(result!!.rejected.containsKey("GROUND_NOT_STABLE"))
        assertFalse(result!!.solved)
    }

}
