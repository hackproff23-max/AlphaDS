.class Lcom/dsemu/drastic/LoadingActivity$a;
.super Ljava/lang/Object;
.source "LoadingActivity.java"

# interfaces
.implements Ljava/lang/Runnable;


# instance fields
.field final a:Lcom/dsemu/drastic/LoadingActivity;


# direct methods
.method constructor <init>(Lcom/dsemu/drastic/LoadingActivity;)V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    iput-object p1, p0, Lcom/dsemu/drastic/LoadingActivity$a;->a:Lcom/dsemu/drastic/LoadingActivity;

    return-void
.end method


# virtual methods
.method public run()V
    .locals 1

    iget-object v0, p0, Lcom/dsemu/drastic/LoadingActivity$a;->a:Lcom/dsemu/drastic/LoadingActivity;

    invoke-virtual {v0}, Lcom/dsemu/drastic/LoadingActivity;->step()V

    return-void
.end method
