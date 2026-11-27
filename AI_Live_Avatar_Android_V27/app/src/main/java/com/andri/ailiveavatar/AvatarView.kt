package com.andri.ailiveavatar

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.sin

class AvatarView(context: Context): View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG); private var mouth=0.10f; private var manualMouth=0.10f; private var mouthTarget=0.10f; private var blink=0f; private var yaw=0f; private var pitch=0f; private var smile=0f; private var audio=0f; private var smooth=0.72f; private var intensity=1f; private var idle=true; private var mirror=false; private var t=0f; private var bg:Bitmap?=null; private var avatarBmp:Bitmap?=null; private var headFollow=true; private var avatarScale=0.92f; private var avatarAlpha=1f; private var avatarX=0f; private var avatarY=0f; private var faceVisible=false; private var trackingQuality=0f; private var moodSmile=0f; private var moodMouth=0f
    fun setManualMouth(v:Float){manualMouth=v.coerceIn(0f,1f); mouthTarget=manualMouth; invalidate()}
    // Presets: 0 natural, 1 happy, 2 surprised, 3 calm
    fun setMood(preset:Int){
        when(preset){
            1 -> { moodSmile=0.85f; moodMouth=0.12f }
            2 -> { moodSmile=0.12f; moodMouth=0.72f }
            3 -> { moodSmile=0.04f; moodMouth=0.02f }
            else -> { moodSmile=0f; moodMouth=0f }
        }
        smile=(smile*0.35f+moodSmile*0.65f).coerceIn(0f,1f)
        mouthTarget=(mouthTarget*0.35f+moodMouth*0.65f).coerceIn(0f,1f)
        invalidate()
    }
    fun setSmooth(v:Float){smooth=v.coerceIn(0f,1f)}
    fun setAudioLevel(v:Float){
        val attack=0.42f+(1f-smooth)*0.18f
        val release=0.16f+(1f-smooth)*0.12f
        audio += (v-audio)*(if(v>audio) attack else release)
        mouthTarget=(manualMouth*0.12f+audio*0.96f+smile*0.10f).coerceIn(0f,1f)
        mouth += (mouthTarget-mouth)*(0.28f+(1f-smooth)*0.16f)
        invalidate()
    }
    fun setFace(y:Float,pit:Float,eye:Float,sm:Float){yaw=y; pitch=pit; blink=(1-eye).coerceIn(0f,1f); smile=sm.coerceIn(0f,1f); faceVisible=true; trackingQuality=1f; invalidate()}
    fun setFaceLost(){faceVisible=false; trackingQuality=0f; yaw*=0.88f; pitch*=0.88f; blink*=0.88f; smile*=0.90f; invalidate()}
    fun isFaceVisible()=faceVisible
    fun setBackgroundBitmap(b:Bitmap?){bg=b; invalidate()}
    fun setAvatarBitmap(b:Bitmap?){avatarBmp=b; invalidate()}
    fun currentAvatarBitmap():Bitmap? = avatarBmp
    fun setIntensity(v:Float){intensity=v.coerceIn(0f,2f); invalidate()}
    fun setIdle(v:Boolean){idle=v; invalidate()}
    fun setMirror(v:Boolean){mirror=v; invalidate()}
    fun setHeadFollow(v:Boolean){headFollow=v; invalidate()}
    fun setAvatarScale(v:Float){avatarScale=v.coerceIn(0.55f,1.25f); invalidate()}
    fun setAvatarAlpha(v:Float){avatarAlpha=v.coerceIn(0f,1f); invalidate()}
    fun resetFraming(){avatarScale=0.92f; avatarX=0f; avatarY=0f; avatarAlpha=1f; invalidate()}
    fun setAvatarOffsetX(v:Float){avatarX=v.coerceIn(-0.35f,0.35f); invalidate()}
    fun setAvatarOffsetY(v:Float){avatarY=v.coerceIn(-0.35f,0.35f); invalidate()}
    override fun onDraw(c:Canvas){super.onDraw(c); val w=width.toFloat(); val h=height.toFloat(); drawBg(c,w,h); t+=0.018f; mouth += (mouthTarget-mouth)*0.10f
        val bob=if(idle) sin(t*1.4f)*4f*intensity else 0f; val followX=if(headFollow) yaw*w*.045f*intensity else 0f; val followY=if(headFollow) pitch*h*.012f*intensity else 0f; val cx=w/2f+followX+yaw*w*.025f*intensity; val faceCy=h*.37f+followY+pitch*h*.010f*intensity+bob
        val bmp=avatarBmp
        if(bmp!=null){
            val scale=maxOf(w/bmp.width,h/bmp.height)*avatarScale; val bw=bmp.width*scale; val bh=bmp.height*scale
            c.save(); if(mirror)c.scale(-1f,1f,w/2f,h/2f)
            val rot=yaw*4.0f*intensity; c.rotate(rot,w/2f,h*.43f);
            p.alpha=(255f*avatarAlpha).toInt(); val left=(w-bw)/2f + avatarX*w + followX; val top=h*.08f + avatarY*h + followY; c.drawBitmap(bmp,null,RectF(left,top,left+bw,top+bh),p); c.restore()
            // lightweight expression cues over photo; kept subtle so the source photo remains recognizable
            val cueY=h*.42f+pitch*h*.01f; p.color=Color.argb((45*smile*intensity).toInt().coerceIn(0,55),255,210,210); c.drawOval(w*.30f,cueY,w*.43f,cueY+h*.025f,p); c.drawOval(w*.57f,cueY,w*.70f,cueY+h*.025f,p)
            if(blink>.55f){p.color=Color.argb(80,40,30,30); p.strokeWidth=4f; p.style=Paint.Style.STROKE; c.drawLine(w*.34f,cueY,w*.42f,cueY,p); c.drawLine(w*.58f,cueY,w*.66f,cueY,p); p.style=Paint.Style.FILL}
            val my=h*.52f; val mw=w*.075f*(1f+smile*.25f); val op=h*(.006f+mouth*.045f)*intensity; p.color=Color.argb(110,105,42,49); c.drawOval(w/2f-mw,my-op,w/2f+mw,my+op,p)
            postInvalidateDelayed(16); return
        }
        val fw=w*.285f; val fh=h*.40f; val sway=if(idle) sin(t)*2.2f*intensity else 0f
        p.shader=LinearGradient(0f,h*.65f,0f,h,Color.rgb(31,34,40),Color.rgb(8,9,12),Shader.TileMode.CLAMP); c.drawRoundRect(cx-fw*1.42f+sway,h*.65f,cx+fw*1.42f+sway,h+30f,70f,70f,p); p.shader=null
        p.color=Color.rgb(220,164,139); c.drawRoundRect(cx-fw*.25f,faceCy+fh*.42f,cx+fw*.25f,h*.72f,24f,24f,p)
        p.color=Color.rgb(31,24,25); c.drawOval(cx-fw*1.13f,faceCy-fh*.62f,cx+fw*1.13f,faceCy+fh*.62f,p)
        p.color=Color.rgb(236,191,163); c.drawOval(cx-fw,faceCy-fh*.52f,cx+fw,faceCy+fh*.52f,p)
        p.color=Color.rgb(31,24,25); val path=Path().apply{moveTo(cx-fw*1.02f,faceCy-fh*.45f); quadTo(cx-fw*.55f,faceCy-fh*.72f,cx-fw*.12f,faceCy-fh*.57f); quadTo(cx+fw*.35f,faceCy-fh*.75f,cx+fw*1.02f,faceCy-fh*.42f); lineTo(cx+fw*.82f,faceCy-fh*.10f); quadTo(cx,faceCy-fh*.28f,cx-fw*.82f,faceCy-fh*.08f); close()}; c.drawPath(path,p)
        p.color=Color.WHITE; val ey=faceCy; val dx=fw*.40f; val ew=fw*.32f; val eh=maxOf(2f,fh*.085f*(1-blink)); c.drawOval(cx-dx-ew/2,ey-eh,cx-dx+ew/2,ey+eh,p); c.drawOval(cx+dx-ew/2,ey-eh,cx+dx+ew/2,ey+eh,p)
        p.color=Color.rgb(42,32,31); val ps=yaw*5f; c.drawCircle(cx-dx+ps,ey,maxOf(3f,eh*.55f),p); c.drawCircle(cx+dx+ps,ey,maxOf(3f,eh*.55f),p)
        val my=faceCy+fh*.31f; val mw=fw*(.34f+smile*.12f); val op=fh*(.012f+mouth*.105f); p.color=Color.rgb(105,42,49); c.drawOval(cx-mw,my-op,cx+mw,my+op,p)
        postInvalidateDelayed(16)
    }
    private fun drawBg(c:Canvas,w:Float,h:Float){val b=bg;if(b!=null){val s=maxOf(w/b.width,h/b.height);val bw=b.width*s;val bh=b.height*s;c.drawBitmap(b,null,RectF((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2),p);return};p.shader=LinearGradient(0f,0f,w,h,Color.rgb(40,49,64),Color.rgb(10,12,17),Shader.TileMode.CLAMP);c.drawRect(0f,0f,w,h,p);p.shader=null}
}
